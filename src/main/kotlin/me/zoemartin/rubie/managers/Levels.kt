package me.zoemartin.rubie.managers

import me.zoemartin.rubie.Bot.addListener
import me.zoemartin.rubie.core.AutoConfig
import me.zoemartin.rubie.core.GuildCommandEvent
import me.zoemartin.rubie.core.interfaces.ModuleInterface
import me.zoemartin.rubie.core.util.DatabaseUtil
import me.zoemartin.rubie.database.entities.LevelConfig
import me.zoemartin.rubie.database.entities.LevelConfig.Announcements
import me.zoemartin.rubie.database.entities.UserConfig
import me.zoemartin.rubie.database.entities.UserLevel
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.jodah.expiringmap.ExpiringMap
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Function
import javax.annotation.Nonnull

object Levels : ListenerAdapter(), ModuleInterface {
    private val log = LoggerFactory.getLogger(Levels::class.java)
    private val levels: MutableMap<String, MutableMap<String, UserLevel>> = ConcurrentHashMap()
    private val configs: MutableMap<String, LevelConfig> = ConcurrentHashMap()
    private val timeout: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    private val userConfigs: MutableMap<String, MutableMap<String, UserConfig>> = ConcurrentHashMap()

    override fun init() {
        addListener(this)
        AutoConfig.registerConverter(
            Announcements::class.java
        ) { _: GuildCommandEvent?, s: String ->
            EnumSet.allOf(
                Announcements::class.java
            ).stream()
                .filter { a: Announcements ->
                    if (s.matches(Regex.fromLiteral("\\d"))) a.raw() == s.toInt() else a.name.equals(
                        s,
                        ignoreCase = true
                    )
                }
                .findAny()
                .orElseThrow({ IllegalArgumentException() })
        }
        AutoConfig.registerConverter(
            UserConfig.Color::class.java
        ) { _: GuildCommandEvent?, s: String ->
            if (s.matches(Regex.fromLiteral("reset|role"))) return@registerConverter null
            if (s.matches(Regex.fromLiteral("^(#|0x)([a-fA-F0-9]{6}|[a-fA-F0-9]{3})$"))) return@registerConverter UserConfig.Color(
                Integer.decode(s)
            ) else throw IllegalArgumentException()
        }
    }

    override fun initLate() {
        initLevels()
        initConfigs()
        initUserConfigs()
    }

    private fun initLevels() {
        levels.putAll(
            DatabaseUtil.loadGroupedMap(
                "from UserLevel",
                UserLevel::class.java,
                UserLevel::guildId, UserLevel::userId, Function.identity()
            )
        )
        levels.forEach { (s: String?, stringUserLevelMap: Map<String, UserLevel>) ->
            log.info("Loaded '${stringUserLevelMap.keys.size}' levels for '$s'")
        }
    }

    private fun initUserConfigs() {
        userConfigs.putAll(
            DatabaseUtil.loadGroupedMap(
                "from UserConfig",
                UserConfig::class.java,
                UserConfig::guildId, UserConfig::userId, Function.identity()
            )
        )
        userConfigs.forEach { (s: String?, stringUserLevelMap: Map<String, UserConfig>) ->
            log.info("Loaded '${stringUserLevelMap.keys.size}' user configs for '$s'")
        }
    }

    private fun initConfigs() {
        configs.putAll(
            DatabaseUtil.loadMap(
                "from LevelConfig",
                LevelConfig::class.java,
                LevelConfig::guildId, Function.identity(), LevelConfig::guildId
            )
        )
        configs.forEach { (_: String?, levelConfig: LevelConfig) ->
            log.info("Loaded config for '${levelConfig.guildId}' with UUID `${levelConfig.getUUID()}`")
        }
        log.info("Loaded '${configs.keys.size}' configuration files")
    }

    override fun onGuildMessageReceived(@Nonnull event: GuildMessageReceivedEvent) {
        Thread { process(event) }.start()
    }

    private fun process(@Nonnull event: GuildMessageReceivedEvent) {
        if (event.author.isBot) return
        val g = event.guild
        val u = event.author
        val config = getConfig(g)
        if (!config.isEnabled()) return
        if (timeout.getOrDefault(g.id, emptySet()).contains(u.id)) return
        if (config.getBlacklistedChannels().contains(event.channel.id)) return
        if (config.getBlockedUsers().contains(u.id)) return
        if (event.member!!
                .roles.stream().anyMatch { role: Role ->
                    config.getBlacklistedRoles().contains(role.id)
                }
        ) return
        val level = getUserLevel(g, u)
        val before = calcLevel(level.exp)
        level.addExp(ThreadLocalRandom.current().nextInt(15, 26))
        val after = calcLevel(level.exp)
        timeout.computeIfAbsent(
            g.id
        ) { k: String? ->
            Collections.newSetFromMap(
                ExpiringMap.builder().expiration(1, TimeUnit.MINUTES)
                    .build()
            )
        }
            .add(u.id)
        if (after > before) {
            levelUp(event, after)
        }
        DatabaseUtil.updateObject(level)
    }

    private fun levelUp(event: GuildMessageReceivedEvent, level: Int) {
        val g = event.guild
        val roles = getConfig(g).getRewardRoles(level)
        val rewards = roles.stream().filter { s: String? ->
            val r: Role = g.getRoleById((s)!!) ?: return@filter false
            !event.member!!.roles.contains(r)
        }.count()
        roles.forEach(Consumer { s: String? ->
            val r: Role? = g.getRoleById((s)!!)
            if (r != null) g.addRoleToMember((event.member)!!, r).queue()
        })
        val config = getConfig(g)
        val name = event.member!!.effectiveName
        when (config.getAnnouncements()) {
            Announcements.ALL -> if (rewards > 0) event.author.openPrivateChannel().complete().sendMessage(
                "Hey, $name! Congratulations on hitting level $level in ${g.name}! " +
                        "Hope you enjoy your new server privileges, and hey, thanks for being here " +
                        "\uD83D\uDC9A"
            ).queue() else event.author.openPrivateChannel().complete().sendMessage(
                "Hey, $name! Congratulations on hitting level $level in ${g.name}! " +
                        "Thanks for being here! " +
                        "\uD83D\uDC9A"
            ).queue()
            Announcements.REWARDS -> if (rewards > 0) event.author.openPrivateChannel().complete().sendMessage(
                "Hey, $name! Congratulations on hitting level $level in ${g.name}! " +
                        "Hope you enjoy your new server privileges, and hey, thanks for being here " +
                        "\uD83D\uDC9A"
            ).queue()
            else -> {
            }
        }
        log.info("User ${event.author.asTag}/(${event.author.id}) leveled up on $g. Reward Roles ${roles.joinToString()}")
    }

    @Nonnull
    fun getUserLevel(g: Guild, user: User) = levels.computeIfAbsent(
        g.id
    ) { ConcurrentHashMap() }.computeIfAbsent(
        user.id
    ) { DatabaseUtil.saveObject(UserLevel(g.id, user.id)) }

    @Nonnull
    fun getUserConfig(m: Member) = userConfigs.computeIfAbsent(
        m.guild.id
    ) { ConcurrentHashMap() }
        .computeIfAbsent(m.id) {
            DatabaseUtil.saveObject(UserConfig(m))
        }

    fun getLevels(g: Guild): Collection<UserLevel> = levels.getOrDefault(g.id, emptyMap()).values

    fun importLevel(userLevel: UserLevel) {
        val lvls = levels.computeIfAbsent(
            userLevel.userId
        ) { ConcurrentHashMap() }
        if (lvls.containsKey(userLevel.userId)) DatabaseUtil.deleteObject(lvls[userLevel.userId])
        lvls[userLevel.userId] = userLevel
        DatabaseUtil.saveObject(userLevel)
    }

    fun getConfig(g: Guild) = configs.computeIfAbsent(
        g.id
    ) {
        DatabaseUtil.saveObject(LevelConfig(g.id, false))
    }

    fun clearGuildCache(g: Guild) {
        if (!levels.containsKey(g.id)) return
        levels[g.id]!!.clear()
    }

    fun calcExp(lvl: Int) = ((5.0 / 6.0) * lvl * ((2 * Math.pow(lvl.toDouble(), 2.0)) + (27 * lvl) + 91)).toInt()

    fun calcLevel(exp: Int): Int {
        val x = (exp + 1).toDouble()
        val pow = Math.cbrt(
            (Math.sqrt(3.0) * Math.sqrt(
                3888.0 * Math.pow(
                    x,
                    2.0
                ) + (291600.0 * x) - 207025.0
            )) - (108.0 * x) - 4050.0
        )
        return ((-pow / (2.0 * Math.pow(3.0, 2.0 / 3.0) * Math.pow(5.0, 1.0 / 3.0))) - (
                (61.0 * Math.cbrt(5.0 / 3.0)) / (2.0 * pow)) - (9.0 / 2.0)).toInt()
    }
}