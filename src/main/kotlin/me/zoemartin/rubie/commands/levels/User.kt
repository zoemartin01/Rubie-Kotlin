package me.zoemartin.rubie.commands.levels

import me.zoemartin.rubie.core.AutoConfig
import me.zoemartin.rubie.core.CommandPerm
import me.zoemartin.rubie.core.GuildCommandEvent
import me.zoemartin.rubie.core.annotations.Command
import me.zoemartin.rubie.core.annotations.CommandOptions
import me.zoemartin.rubie.core.annotations.SubCommand
import me.zoemartin.rubie.core.interfaces.GuildCommand
import me.zoemartin.rubie.core.util.CacheUtils
import me.zoemartin.rubie.core.util.Parser
import me.zoemartin.rubie.database.entities.UserConfig
import me.zoemartin.rubie.database.entities.UserLevel
import me.zoemartin.rubie.managers.Levels
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.User
import java.time.Instant

@Command
@CommandOptions(
    name = "level",
    alias = ["lvl"],
    description = "Shows Levels",
    usage = "[user]",
    perm = CommandPerm.BOT_USER
)
class Level : GuildCommand() {
    override fun run(event: GuildCommandEvent) {
        Show().run(event)
    }

    @SubCommand(Level::class)
    @CommandOptions(name = "show", description = "Shows a users level", usage = "[user]", perm = CommandPerm.BOT_USER)
    private class Show : GuildCommand() {
        override fun run(event: GuildCommandEvent) {
            var u: User? = null
            var arg: String
            val guild = event.guild
            if (event.args.isEmpty()) u = event.user else if (Parser.User.isParsable(lastArg(0, event).also {
                    arg = it
                })) u = CacheUtils.getUser(arg) else if (Parser.User.tagIsParsable(arg)) u = event.jda.getUserByTag(arg)
            if (u == null) u = event.user
            val member = CacheUtils.getMember(event.guild, u.id)
            val level: UserLevel = Levels.getUserLevel(event.guild, u)
            val userConf = if (member == null) null else Levels.getUserConfig(member)
            val exp: Int = level.exp
            val lvl = Levels.calcLevel(exp)
            val expToNext = Levels.calcExp(lvl + 1).toDouble()
            val levels: List<UserLevel> = Levels.getLevels(event.guild)
                .filter { guild.getMemberById(it.userId) != null}
                .sortedByDescending { it.exp }.toList()
            val eb = EmbedBuilder()
                .setThumbnail(u.effectiveAvatarUrl)
                .setFooter(u.asTag)
                .setTimestamp(Instant.now())
            if (userConf?.getColor() != null) {
                eb.setColor(userConf.getColor()!!)
            } else {
                if (member != null) eb.setColor(member.color)
            }
            if (member != null) eb.setTitle("Level $lvl - Rank #${levels.indexOf(level) + 1}")
            else eb.setTitle("Level $lvl")
            eb.addField(
                "${((exp - Levels.calcExp(lvl)) / (expToNext - Levels.calcExp(lvl)) * 100).toLong()}%",
                "$exp/${Levels.calcExp(lvl + 1)}xp",
                true
            )
            event.channel.sendMessage(eb.build()).queue()
        }
    }

    @SubCommand(Level::class)
    @CommandOptions(
        name = "customise",
        description = "Customise your level card!",
        usage = "[key] [value]",
        alias = ["c", "custom"],
        perm = CommandPerm.BOT_USER
    )
    internal class Custom : AutoConfig<UserConfig>() {
        override fun supply(event: GuildCommandEvent): UserConfig {
            return Levels.getUserConfig(event.member)
        }
    }
}

