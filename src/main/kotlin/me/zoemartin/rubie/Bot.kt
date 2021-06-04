package me.zoemartin.rubie

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.zoemartin.rubie.core.managers.JobManager
import me.zoemartin.rubie.core.managers.ModuleManager
import me.zoemartin.rubie.core.util.DatabaseUtil
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message.MentionType
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.restaction.MessageAction
import net.dv8tion.jda.api.utils.Compression
import net.dv8tion.jda.api.utils.MemberCachePolicy
import org.hibernate.cfg.Configuration
import org.hibernate.cfg.Environment
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.*
import kotlin.system.exitProcess


object Bot {
    @JvmStatic
    lateinit var jda: JDA

    @JvmStatic
    val owner = "212591138945630213"

    @JvmStatic
    lateinit var properties: Properties
    var configFile: File? = null
    private lateinit var builder: JDABuilder
    private var exitCode = 0
    private val log = LoggerFactory.getLogger(Bot::class.java)
    private var startedWithEnvVars: Boolean = false
    private val envvarJson = Bot::class.java.getResource("/envvar.json")!!.readText()

    class Bot : ListenerAdapter() {
        override fun onShutdown(event: ShutdownEvent) {
            exitProcess(exitCode)
        }

        override fun onReady(event: ReadyEvent) {
            JobManager.init()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("logFilename", "rubie_" + DateTime.now().toString("yyyy-MM-dd_HH-mm-ss") + ".log")
        properties = Properties()

        if (args.size == 1 && args[0].lowercase().contentEquals("envvar")) {
            startedWithEnvVars = true

            val envVars = System.getenv()

            val keys: Map<String, Map<String, Any?>> = Gson().fromJson(
                envvarJson, object : TypeToken<HashMap<String?, Map<String, Any?>?>?>() {}.type
            )

            keys.values.forEach { map ->
                run {
                    val key = (map["var"] as String).uppercase()
                    val optional = map["optional"] as Boolean
                    if (!optional && !envVars.containsKey(key)) throw IllegalArgumentException("Enviroment Variable '$key' missing")
                    val value = if (envVars.containsKey(key)) envVars[key] else map["default"]
                    properties[map["prop"]] = value
                }
            }
        } else {
            configFile = if (args.isEmpty()) File(".env")
            else File(args[0])
            reloadConfig()
        }
        initBuilder()
        ModuleManager.init()
        initDB()
        ModuleManager.initLate()

        builder.enableIntents(
            GatewayIntent.GUILD_VOICE_STATES,
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_PRESENCES
        )
        builder.setMemberCachePolicy(MemberCachePolicy.ALL)
        builder.setBulkDeleteSplittingEnabled(false)
        builder.setCompression(Compression.NONE)
        builder.addEventListeners(Bot())
        val deny = EnumSet.of(
            MentionType.EVERYONE, MentionType.HERE,
            MentionType.ROLE
        )
        MessageAction.setDefaultMentions(EnumSet.complementOf(deny))
        jda = builder.build()
    }

    private fun initDB(
        url: String = properties.getProperty("database.url"),
        user: String = properties.getProperty("database.username"),
        pw: String = properties.getProperty("database.password")
    ) {
        val config = Configuration()
        val settings = Properties()
        settings[Environment.DRIVER] = "org.postgresql.Driver"
        settings[Environment.URL] = url
        settings[Environment.USER] = user
        settings[Environment.PASS] = pw
        settings[Environment.DIALECT] = "org.hibernate.dialect.PostgreSQL82Dialect"
        settings[Environment.POOL_SIZE] = 10
        settings[Environment.SHOW_SQL] = false
        settings[Environment.HBM2DDL_AUTO] = "update"
        config.properties = settings
        DatabaseUtil.setConfig(config)
    }

    private fun initBuilder(
        token: String = properties.getProperty("bot.token")
    ) {
        builder = JDABuilder.createDefault(token)
    }

    @JvmStatic
    fun reloadConfig() {
        if (startedWithEnvVars) return
        try {
            properties.load(FileReader(configFile!!))
        } catch (e: IOException) {
            log.error("Error loading configuration file!")
        }
    }

    @JvmStatic
    fun addListener(vararg listeners: Any?) {
        builder.addEventListeners(*listeners)
    }

    @JvmStatic
    fun shutdownWithCode(code: Int, force: Boolean) {
        exitCode = code
        println(exitCode)
        if (force) jda.shutdownNow() else jda.shutdown()
    }
}