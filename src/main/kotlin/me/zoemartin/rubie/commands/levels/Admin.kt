package me.zoemartin.rubie.commands.levels

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import me.zoemartin.rubie.Bot.jda
import me.zoemartin.rubie.core.AutoConfig
import me.zoemartin.rubie.core.CommandPerm
import me.zoemartin.rubie.core.GuildCommandEvent
import me.zoemartin.rubie.core.Job.CommonKeys
import me.zoemartin.rubie.core.annotations.CommandOptions
import me.zoemartin.rubie.core.annotations.SubCommand
import me.zoemartin.rubie.core.annotations.SubCommand.AsBase
import me.zoemartin.rubie.core.exceptions.CommandArgumentException
import me.zoemartin.rubie.core.exceptions.ReplyError
import me.zoemartin.rubie.core.exceptions.UnexpectedError
import me.zoemartin.rubie.core.interfaces.GuildCommand
import me.zoemartin.rubie.core.interfaces.JobProcessorInterface
import me.zoemartin.rubie.core.managers.JobManager
import me.zoemartin.rubie.core.util.*
import me.zoemartin.rubie.database.entities.LevelConfig
import me.zoemartin.rubie.database.entities.UserLevel
import me.zoemartin.rubie.jobs.TempBlockLevels
import me.zoemartin.rubie.managers.Levels
import me.zoemartin.rubie.managers.Levels.calcLevel
import me.zoemartin.rubie.managers.Levels.clearGuildCache
import me.zoemartin.rubie.managers.Levels.getConfig
import me.zoemartin.rubie.managers.Levels.getLevels
import me.zoemartin.rubie.managers.Levels.getUserLevel
import me.zoemartin.rubie.managers.Levels.importLevel
import me.zoemartin.rubie.modules.pagedEmbeds.PageListener
import me.zoemartin.rubie.modules.pagedEmbeds.PagedEmbed
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.entities.User
import org.joda.time.DateTime
import org.joda.time.Period
import org.joda.time.format.PeriodFormatterBuilder
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.Stream

@SubCommand(Level::class)
@CommandOptions(
    name = "leaderboard",
    description = "Shows the current leaderboard",
    usage = "[full]",
    perm = CommandPerm.BOT_MANAGER
)
@AsBase(name = "leaderboard")
internal class Leaderboard : GuildCommand() {
    override fun run(event: GuildCommandEvent) {
        val args = event.args
        val levels: List<UserLevel>
        val start: Int
        val guild = event.guild
        if (args.isNotEmpty() && args[0].equals("full", ignoreCase = true)) {
            levels = getLevels(event.guild).sortedByDescending { it.exp }.toList()
            start = if (args.size > 1 && Parser.Int.isParsable(args[1])) args[1].toInt() else 1
        } else {
            levels = getLevels(event.guild).filter { guild.getMemberById(it.userId) != null }
                .sortedByDescending { it.exp }.toList()
            start = if (args.isNotEmpty() && Parser.Int.isParsable(args[0])) args[0].toInt() else 1
        }
        val p = PagedEmbed(
            EmbedUtil.pagedDescription(
                EmbedBuilder().setTitle("Leaderboard").build(),
                levels
                    .map {
                        val u: User = jda.getUserById(it.userId)
                            ?: return@map "${levels.indexOf(it) + 1}. `${it.userId}` - Level: `${calcLevel(it.exp)}` - `${it.exp}xp`\n"
                        "${levels.indexOf(it) + 1}. ${u.asMention} - Level: `${calcLevel(it.exp)}` - `${it.exp}xp`\n"
                    }.toList()
            ), event, start
        )
        PageListener.add(p)
    }
}

@SubCommand(Level::class)
@CommandOptions(
    name = "config",
    description = "Level Configuration",
    perm = CommandPerm.BOT_ADMIN,
    alias = ["conf"]
)
@AsBase(name = "levelconfig", alias = ["lvlconf"])
class Config : GuildCommand() {
    override fun run(event: GuildCommandEvent) {
        throw CommandArgumentException()
    }

    @SubCommand(Config::class)
    @CommandOptions(
        name = "param",
        description = "Level Parameter Configuration",
        perm = CommandPerm.BOT_ADMIN,
        alias = ["parameter"],
        usage = "[key] [value]"
    )
    internal class Param : AutoConfig<LevelConfig?>() {
        override fun supply(event: GuildCommandEvent): LevelConfig {
            return getConfig(event.guild)
        }
    }

    @SubCommand(Config::class)
    @CommandOptions(
        name = "setxp",
        description = "Sets a users exp",
        usage = "<xp> <user>",
        perm = CommandPerm.BOT_ADMIN
    )
    private class SetExp : GuildCommand() {
        override fun run(event: GuildCommandEvent) {
            Check.check(
                event.args.size == 2 && Parser.Int.isParsable(
                    event.args[0]
                )
                        && Parser.User.isParsable(event.args[1])
            ) { CommandArgumentException() }
            val xp = Parser.Int.parse(event.args[0])
            val u = CacheUtils.getUserExplicit(event.args[1])
            Check.check(xp >= 0) { ReplyError("Xp must be above 0") }
            val level = getUserLevel(event.guild, u)
            level.exp = xp
            DatabaseUtil.updateObject(level)
            event.addCheckmark()
            event.reply("Levels", "Set ${u.asMention}'s xp to `$xp`").queue()
        }
    }

    /**
     * Input file format: *.json
     *
     *
     * [ { "Userid": id, "Exp": exp }, ... ]
     */
    @SubCommand(Config::class)
    @CommandOptions(name = "import", description = "Import Levels from an Attachment", perm = CommandPerm.BOT_ADMIN)
    private class Import : GuildCommand() {
        override fun run(event: GuildCommandEvent) {
            Check.check(
                event.args.isEmpty()
            ) { CommandArgumentException() }
            Check.check(
                event.attachments.size == 1
            ) { CommandArgumentException() }
            val m = event.channel.sendMessage("Okay... this might take a while").complete()
            val start = Instant.now()
            val guildId = event.guild.id
            val listType = object : TypeToken<ArrayList<ImportLevel?>?>() {}.type
            var levels: Map<String?, Int?>
            try {
                InputStreamReader(
                    event.attachments[0]
                        .retrieveInputStream()[1, TimeUnit.MINUTES]
                ).use { ir ->
                    val br = BufferedReader(ir)
                    levels =
                        Gson().fromJson<List<ImportLevel>>(br, listType).associate { it.userId to it.exp }
                }
            } catch (e: InterruptedException) {
                throw UnexpectedError(e)
            } catch (e: ExecutionException) {
                throw UnexpectedError(e)
            } catch (e: TimeoutException) {
                throw UnexpectedError(e)
            } catch (e: IOException) {
                throw UnexpectedError(e)
            }
            levels.forEach { (s: String?, integer: Int?) -> importLevel(UserLevel(guildId, s!!, integer!!)) }
            event.addCheckmark()
            m.delete().complete()
            val p = PagedEmbed(
                EmbedUtil.pagedDescription(
                    EmbedBuilder().setTitle("Imported Levels: ${levels.size}").build(),
                    Stream.concat(
                        Stream.of("Time taken: ${Duration.between(start, Instant.now()).toSeconds()} seconds\n"),
                        levels.entries.sortedBy { it.value }.stream()
                            .map { (key, value) ->
                                val u = jda.getUserById(key!!)
                                    ?: return@map "User: `$key` - Level: `${calcLevel(value!!)}` - Exp: `$value`\n"
                                "User: ${u.asMention} - Level: `${calcLevel(value!!)}` - Exp: `$value`\n"

                            }).collect(Collectors.toList())
                ), event
            )
            PageListener.add(p)
        }

        private data class ImportLevel(
            @field:SerializedName(value = "Userid") val userId: String,
            @field:SerializedName(value = "Exp") val exp: Int
        ) : Serializable
    }

    @SubCommand(Config::class)
    @CommandOptions(
        name = "clear",
        description = "WARNING: CLEARS ALL LEVELS FROM THE DATABASE",
        perm = CommandPerm.BOT_ADMIN
    )
    private class Clear : GuildCommand() {
        override fun run(event: GuildCommandEvent) {
            Check.check(event.args.size >= 1 && event.args[0].equals("--agree")) {
                ReplyError(
                    "This action will clear the entire level database for this server. " +
                            "To confirm this action rerun this command with the argument `--agree`!"
                )
            }
            val start = Instant.now()
            val m = event.channel.sendMessage("Okay... this might take a while").complete()
            getLevels(event.guild).forEach(Consumer { objects: UserLevel? ->
                DatabaseUtil.deleteObject(
                    objects
                )
            })
            clearGuildCache(event.guild)
            event.addCheckmark()
            m.delete().complete()
            event.reply(
                "Level Config",
                "Successfully cleared all Levels\nTime taken: ${
                    Duration.between(start, Instant.now()).toSeconds()
                } seconds"
            ).queue()
        }
    }

    @SubCommand(Config::class)
    @CommandOptions(
        name = "rewards",
        description = "Manage Role Rewards",
        perm = CommandPerm.BOT_ADMIN,
        alias = ["rolerewards"],
        botPerms = [Permission.MANAGE_ROLES]
    )
    private class RoleRewards : GuildCommand() {
        override fun run(event: GuildCommandEvent) {
            throw CommandArgumentException()
        }

        @SubCommand(RoleRewards::class)
        @CommandOptions(
            name = "add",
            description = "Adds a role reward",
            usage = "<level> <role>",
            perm = CommandPerm.BOT_ADMIN,
            botPerms = [Permission.MANAGE_ROLES]
        )
        private class Add : GuildCommand() {
            override fun run(event: GuildCommandEvent) {
                Check.check(event.args.size > 1 && Parser.Int.isParsable(event.args[0])) { CommandArgumentException() }
                val rRef = lastArg(1, event)
                val r = Parser.Role.getRole(event.guild, rRef)
                val level = Parser.Int.parse(event.args[0])
                Check.entityReferenceNotNull(r, Role::class.java, rRef)
                Check.check(level > 0) { ReplyError("Error, Level must be bigger than 0") }
                val config = getConfig(event.guild)
                config.addRewardRole(r!!.id, level)
                DatabaseUtil.updateObject(config)
                event.addCheckmark()
                event.reply("Level Rewards", "Added ${r.asMention} to Level $level").queue()
            }
        }

        @SubCommand(RoleRewards::class)
        @CommandOptions(
            name = "remove",
            description = "Removes a role reward",
            usage = "<level> <role>",
            perm = CommandPerm.BOT_ADMIN
        )
        private class Remove : GuildCommand() {
            override fun run(event: GuildCommandEvent) {
                Check.check(
                    event.args.size > 1
                ) { CommandArgumentException() }
                val rRef = lastArg(1, event)
                val r = Parser.Role.getRole(event.guild, rRef)
                val level = Parser.Int.parse(event.args[0])
                Check.entityReferenceNotNull(
                    r,
                    Role::class.java, rRef
                )
                Check.check(level > 0) {
                    ReplyError(
                        "Error, Level must be bigger than 0"
                    )
                }
                val config = getConfig(event.guild)
                if (!config.removeRewardRole(level, r!!.id)) return
                DatabaseUtil.updateObject(config)
                event.addCheckmark()
                event.reply("Level Rewards", "Removed ${r.asMention} from Level $level").queue()
            }
        }

        @SubCommand(RoleRewards::class)
        @CommandOptions(name = "list", description = "Lists all role rewards", perm = CommandPerm.BOT_ADMIN)
        private class list : GuildCommand() {
            override fun run(event: GuildCommandEvent) {
                Check.check(
                    event.args.isEmpty()
                ) { CommandArgumentException() }
                val (_, _, _, _, _, rewardRoles) = getConfig(event.guild)
                val p = PagedEmbed(
                    EmbedUtil.pagedDescription(
                        EmbedBuilder().setTitle("Role Rewards").build(),
                        rewardRoles.entries.stream()
                            .map { (key, value) ->
                                "Level: `$key` - Role(s): ${
                                    value.joinToString {
                                        val r = event.guild.getRoleById(it)
                                        r?.asMention ?: it
                                    }
                                }\n"
                            }.collect(Collectors.toList())
                    ), event
                )
                PageListener.add(p)
                event.addCheckmark()
            }
        }
    }

    @SubCommand(Config::class)
    @CommandOptions(name = "blacklist", description = "Blacklist Config", perm = CommandPerm.BOT_ADMIN, alias = ["bl"])
    internal class BlackList : GuildCommand() {
        override fun run(event: GuildCommandEvent) {
            throw CommandArgumentException()
        }

        @SubCommand(BlackList::class)
        @CommandOptions(
            name = "list",
            description = "List all blacklistings",
            perm = CommandPerm.BOT_ADMIN,
            alias = ["l"]
        )
        private class list : GuildCommand() {
            override fun run(event: GuildCommandEvent) {
                val g = event.guild
                val config = getConfig(g)
                val roles = config.getBlacklistedRoles()
                val channels = config.getBlacklistedChannels()
                val users = config.getBlockedUsers()
                if (roles.isEmpty() && channels.isEmpty()) return
                val p = PagedEmbed(
                    EmbedUtil.pagedDescription(
                        EmbedBuilder().setTitle("Blacklistings").build(),
                        Stream.of(roles.stream().filter { s: String? -> !s!!.isEmpty() }
                            .map { s: String? ->
                                if (g.getRoleById(s!!) == null) s else g.getRoleById(s)!!.asMention
                            },
                            channels.stream().filter { s: String? -> !s!!.isEmpty() }.map { s: String? ->
                                if (g.getTextChannelById(s!!) == null
                                ) s else g.getTextChannelById(s)!!.asMention
                            },
                            users.stream().filter { s: String -> !s.isEmpty() }.map { s: String? ->
                                if (CacheUtils.getUser(s) == null) s else CacheUtils.getUser(s)!!
                                    .asMention
                            })
                            .reduce { o1, o2 -> Stream.concat(o1, o2) }
                            .orElseGet { Stream.empty() }
                            .map { s: String? -> "$s\n" }.collect(Collectors.toList())
                    ), event
                )
                PageListener.add(p)
            }
        }

        @SubCommand(BlackList::class)
        @CommandOptions(
            name = "channel",
            description = "Blacklist a channel",
            usage = "<channel...>",
            perm = CommandPerm.BOT_ADMIN,
            alias = ["c"]
        )
        private class Channel : GuildCommand() {
            override fun run(event: GuildCommandEvent) {
                Check.check(event.args.isNotEmpty()) { CommandArgumentException() }
                val g = event.guild
                val channels = event.args.map { Parser.Channel.getTextChannel(g, it!!) }.toList()
                val config = getConfig(event.guild)
                channels.forEach(Consumer { c: TextChannel? -> config.addBlacklistedChannel(c!!.id) })
                DatabaseUtil.updateObject(config)
                event.addCheckmark()
                val p = PagedEmbed(
                    EmbedUtil.pagedDescription(
                        EmbedBuilder().setTitle("Blacklisted the following channels: ${channels.size}").build(),
                        channels.map { "${it!!.asMention}\n" }.toList()
                    ),
                    event
                )
                PageListener.add(p)
            }

            @SubCommand(Channel::class)
            @CommandOptions(
                name = "remove",
                description = "Removes a blacklisted channel",
                usage = "<channel>",
                perm = CommandPerm.BOT_ADMIN,
                alias = ["rm"]
            )
            private class Remove : GuildCommand() {
                override fun run(event: GuildCommandEvent) {
                    Check.check(event.args.isNotEmpty()) { CommandArgumentException() }
                    val cRef = lastArg(0, event)
                    val c = Parser.Channel.getTextChannel(event.guild, cRef)
                    Check.entityReferenceNotNull(c, TextChannel::class.java, cRef)
                    val config = getConfig(event.guild)
                    if (!config.removeBlacklistedChannel(c!!.id)) return
                    DatabaseUtil.updateObject(config)
                    event.addCheckmark()
                    event.reply("Level Blacklist", "Unblacklisted ${c.asMention}").queue()
                }
            }
        }

        @SubCommand(BlackList::class)
        @CommandOptions(
            name = "role",
            description = "Blacklist a role",
            usage = "<role>",
            perm = CommandPerm.BOT_ADMIN,
            alias = ["r"]
        )
        private class role : GuildCommand() {
            override fun run(event: GuildCommandEvent) {
                Check.check(event.args.isNotEmpty()) { CommandArgumentException() }
                val rRef = lastArg(0, event)
                val r = Parser.Role.getRole(event.guild, rRef)
                Check.entityReferenceNotNull(r, Role::class.java, rRef)
                val config = getConfig(event.guild)
                config.addBlacklistedRole(r!!.id)
                DatabaseUtil.updateObject(config)
                event.addCheckmark()
                event.reply("Level Blacklist", "Blacklisted ${r.asMention}").queue()
            }

            @SubCommand(role::class)
            @CommandOptions(
                name = "remove",
                description = "Removes a blacklisted role",
                usage = "<role>",
                perm = CommandPerm.BOT_ADMIN,
                alias = ["rm"]
            )
            private class Remove : GuildCommand() {
                override fun run(event: GuildCommandEvent) {
                    Check.check(event.args.isNotEmpty()) { CommandArgumentException() }
                    val rRef = lastArg(0, event)
                    val r = Parser.Role.getRole(event.guild, rRef)
                    Check.entityReferenceNotNull(r, Role::class.java, rRef)
                    val config = getConfig(event.guild)
                    if (!config.removeBlacklistedRole(r!!.id)) return
                    DatabaseUtil.updateObject(config)
                    event.addCheckmark()
                    event.reply("Level Blacklist", "Unblacklisted ${r.asMention}").queue()
                }
            }
        }

        @SubCommand(BlackList::class)
        @CommandOptions(
            name = "user",
            description = "Block a user from gaining levels",
            usage = "<user> [time]",
            perm = CommandPerm.BOT_MANAGER,
            alias = ["u"]
        )
        @AsBase(name = "nolevels")
        private class user : GuildCommand() {
            override fun run(event: GuildCommandEvent) {
                val args = event.args
                Check.check(args.isNotEmpty()) { CommandArgumentException() }
                val uRef = args[0]
                val u = CacheUtils.getUser(Parser.User.parse(uRef))
                var time = -1L
                if (args.size > 1) time = DateTime.now().plus(Period.parse(lastArg(1, event), formatter)).millis
                Check.entityReferenceNotNull(u, User::class.java, uRef)
                val config = getConfig(event.guild)
                config.blockUser(u!!.id)
                if (time > Instant.now().toEpochMilli()) {
                    val settings = ConcurrentHashMap<String, String>()
                    settings[CommonKeys.GUILD] = event.guild.id
                    settings[CommonKeys.USER] = u.id
                    JobManager.newJob(processor, time, settings)
                }
                DatabaseUtil.updateObject(config)
                event.addCheckmark()
                event.reply("Level Blacklist", "Blacklisted ${u.asMention}").queue()
            }

            @SubCommand(user::class)
            @CommandOptions(
                name = "remove",
                description = "Unblocks a user from not gaining levels",
                usage = "<user>",
                perm = CommandPerm.BOT_MANAGER,
                alias = ["rm"]
            )
            private class Remove : GuildCommand() {
                override fun run(event: GuildCommandEvent) {
                    Check.check(event.args.isNotEmpty()) { CommandArgumentException() }
                    val uRef = lastArg(0, event)
                    val u = CacheUtils.getUser(Parser.User.parse(uRef))
                    Check.entityReferenceNotNull(u, User::class.java, uRef)
                    val config = getConfig(event.guild)
                    if (!config.unblocksUser(u!!.id)) return
                    DatabaseUtil.updateObject(config)
                    event.addCheckmark()
                    event.reply("Level Blacklist", "Unblacklisted ${u.asMention}").queue()
                }
            }

            @SubCommand(user::class)
            @CommandOptions(
                name = "list",
                description = "List users blocked from gaining levels",
                perm = CommandPerm.BOT_MANAGER,
                alias = ["l"]
            )
            private class list : GuildCommand() {
                override fun run(event: GuildCommandEvent) {
                    val g = event.guild
                    val config = getConfig(g)
                    val roles = config.getBlacklistedRoles()
                    val channels = config.getBlacklistedChannels()
                    val users = config.getBlockedUsers()
                    if (roles.isEmpty() && channels.isEmpty()) return
                    val p = PagedEmbed(
                        EmbedUtil.pagedDescription(
                            EmbedBuilder().setTitle("Blacklistings").build(),
                            users.filter { s: String -> s.isNotEmpty() }.map { s: String? ->
                                if (CacheUtils.getUser(s) == null) s else CacheUtils.getUser(s)!!.asMention
                            }.map { s: String? -> "$s\n" }.toList()
                        ), event
                    )
                    PageListener.add(p)
                }
            }

            companion object {
                private val processor: JobProcessorInterface = TempBlockLevels()
                private val formatter = PeriodFormatterBuilder()
                    .appendYears()
                    .appendSuffix("y")
                    .appendSeparator(" ", " ", arrayOf(",", ", "))
                    .appendMonths()
                    .appendSuffix("mon")
                    .appendSeparator(" ", " ", arrayOf(",", ", "))
                    .appendWeeks()
                    .appendSuffix("w")
                    .appendSeparator(" ", " ", arrayOf(",", ", "))
                    .appendDays()
                    .appendSuffix("d")
                    .appendSeparator(" ", " ", arrayOf(",", ", "))
                    .appendHours()
                    .appendSuffix("h")
                    .appendSeparator(" ", " ", arrayOf(",", ", "))
                    .appendMinutes()
                    .appendSuffix("m")
                    .appendSeparator(" ", " ", arrayOf(",", ", "))
                    .appendSecondsWithOptionalMillis()
                    .appendSuffix("s")
                    .toFormatter()
            }
        }
    }

}
