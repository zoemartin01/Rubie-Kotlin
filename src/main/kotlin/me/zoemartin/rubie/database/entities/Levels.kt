package me.zoemartin.rubie.database.entities

import me.zoemartin.rubie.core.annotations.DatabaseEntity
import me.zoemartin.rubie.core.annotations.Getter
import me.zoemartin.rubie.core.annotations.Setter
import me.zoemartin.rubie.core.util.DatabaseConverter.StringListConverter
import net.dv8tion.jda.api.entities.Member
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.persistence.*

@DatabaseEntity
@Entity
@Table(name = "levels")
data class UserLevel(
    @Column(name = "guild_id")
    val guildId: String,

    @Column(name = "user_id")
    val userId: String,

    @Column(name = "exp")
    var exp: Int = 0
) {
    @GeneratedValue
    @Column(name = "uuid")
    @Id
    private lateinit var id: UUID

    constructor(guildId: String, userId: String) : this(guildId, userId, 0)

    fun addExp(exp: Int) {
        this.exp += exp
    }
}

@DatabaseEntity
@Entity
@Table(name = "level_userconfigs")
data class UserConfig(
    @Column(name = "guild_id")
    val guildId: String,

    @Column(name = "user_id")
    val userId: String,
) {
    @GeneratedValue
    @Column(name = "id")
    @Id
    private lateinit var id: UUID

    @Column(name = "color")
    private var color: Int? = null

    constructor(member: Member) : this(member.guild.id, member.id)

    @Getter("color")
    fun getColor(): Int? {
        return color
    }

    @Setter(name = "color", type = "hex color or reset")
    fun setColor(color: Color?) {
        if (color == null) this.color = null else this.color = color.raw
    }

    data class Color(val raw: Int) {
        override fun toString(): String {
            return String.format("#%06X", 0xFFFFFF and raw)
        }
    }
}

@DatabaseEntity
@Entity
@Table(name = "level_config")
data class LevelConfig(
    @Column(name = "guild_id")
    val guildId: String,

    @Column(name = "enabled")
    var enabled: Boolean,

    @Column(name = "blacklisted_channels", columnDefinition = "TEXT")
    @Convert(converter = StringListConverter::class)
    val channelBlacklist: MutableCollection<String> = Collections.newSetFromMap(ConcurrentHashMap()),

    @Column(name = "blacklisted_roles", columnDefinition = "TEXT")
    @Convert(converter = StringListConverter::class)
    val roleBlacklist: MutableCollection<String> = Collections.newSetFromMap(ConcurrentHashMap()),

    @Column(name = "blacklisted_users", columnDefinition = "TEXT")
    @Convert(converter = StringListConverter::class)
    val userBlacklist: MutableCollection<String> = Collections.newSetFromMap(ConcurrentHashMap()),

    @Column(name = "rewardroles")
    @ElementCollection(fetch = FetchType.EAGER)
    @MapKeyColumn(name = "role_reward")
    @Convert(converter = StringListConverter::class, attributeName = "value")
    val rewardRoles: MutableMap<Int, MutableCollection<String>> = ConcurrentHashMap(),

    @Column(name = "announcements")
    @Convert(converter = Announcements.Converter::class)
    private var announcements: Announcements = Announcements.NONE
) {
    constructor(guildId: String, enabled: Boolean) : this(guildId, enabled, announcements = Announcements.NONE)

    @GeneratedValue
    @Column(name = "uuid")
    @Id
    private lateinit var id: UUID

    fun getUUID() = id

    @Setter(name = "enabled")
    fun setEnabled(enabled: Boolean?) {
        this.enabled = enabled!!
    }

    @Getter("enabled")
    fun isEnabled() = enabled

    fun getBlacklistedChannels(): Collection<String?> = channelBlacklist
    fun addBlacklistedChannel(channelId: String?) = roleBlacklist.add(channelId!!)
    fun removeBlacklistedChannel(channelId: String?) = roleBlacklist.remove(channelId)

    fun blockUser(userId: String?): Boolean = userBlacklist.add(userId!!)
    fun unblocksUser(userId: String?) = userBlacklist.remove(userId)
    fun getBlockedUsers(): Collection<String> = userBlacklist

    fun getBlacklistedRoles(): Collection<String?> = roleBlacklist
    fun addBlacklistedRole(roleId: String?) = roleBlacklist.add(roleId!!)
    fun removeBlacklistedRole(roleId: String?) = roleBlacklist.remove(roleId)

    fun getRewardRoles(lvl: Int): Collection<String?> = rewardRoles[lvl] ?: emptySet<String>()
    fun addRewardRole(roleId: String?, level: Int) = rewardRoles.computeIfAbsent(level) {
        Collections.newSetFromMap(
            ConcurrentHashMap()
        )
    }.add(roleId!!)
    fun removeRewardRole(level: Int, roleId: String?) =  (rewardRoles[level] ?: HashSet()).remove(roleId)

    @Getter("announcements")
    fun getAnnouncements(): Announcements = announcements

    @Setter(name = "announcements", type = "[0] none /[1] rewards /[2] all")
    fun setAnnouncements(announcements: Announcements) {
        this.announcements = announcements
    }

    enum class Announcements(val raw: Int) {
        NONE(0), REWARDS(1), ALL(2);

        fun raw(): Int {
            return raw
        }

        internal class Converter : AttributeConverter<Announcements?, Int> {
            override fun convertToDatabaseColumn(attribute: Announcements?): Int {
                return attribute!!.raw()
            }

            override fun convertToEntityAttribute(dbData: Int): Announcements? {
                return fromNum(dbData)
            }
        }

        companion object {
            fun fromNum(num: Int?): Announcements? {
                return if (num == null) null
                else EnumSet.allOf(Announcements::class.java)
                    .stream().filter { a: Announcements -> num == a.raw() }
                    .findAny()
                    .orElse(null)
            }
        }
    }
}