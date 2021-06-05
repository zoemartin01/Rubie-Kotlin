package me.zoemartin.rubie.commands.misc

import me.zoemartin.rubie.core.CommandPerm
import me.zoemartin.rubie.core.GuildCommandEvent
import me.zoemartin.rubie.core.annotations.Checks
import me.zoemartin.rubie.core.annotations.Command
import me.zoemartin.rubie.core.annotations.CommandOptions
import me.zoemartin.rubie.core.annotations.SubCommand
import me.zoemartin.rubie.core.exceptions.CommandArgumentException
import me.zoemartin.rubie.core.exceptions.ReplyError
import me.zoemartin.rubie.core.exceptions.UnexpectedError
import me.zoemartin.rubie.core.interfaces.GuildCommand
import me.zoemartin.rubie.core.util.Check
import me.zoemartin.rubie.core.util.Parser
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Icon
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.time.LocalDateTime
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


@Command
@CommandOptions(
    name = "emotes",
    description = "All emote commands"
)
class Emotes : GuildCommand() {
    override fun run(event: GuildCommandEvent?) {
        throw CommandArgumentException()
    }
}


@SubCommand(Emotes::class)
@CommandOptions(
    name = "add",
    description = "Add an emote from an image or another server's emote",
    usage = "<image url|emote> [name]",
    botPerms = [Permission.MANAGE_EMOTES],
    perm = CommandPerm.BOT_MANAGER
)
@Checks.Permissions.Guild(
    Permission.MANAGE_EMOTES
)
@SubCommand.AsBase(name = "addemote", alias = ["steal"])
class Add : GuildCommand() {
    override fun run(event: GuildCommandEvent) {
        val args = event.args
        Check.check(args.isNotEmpty()) { CommandArgumentException() }
        val urlRef = args[0]
        var uri: String?
        val name: String
        if (Parser.Emote.isParsable(urlRef)) {
            val id = Parser.Emote.parse(urlRef)
            uri = String.format(GIF_URI, id)
            if (!exists(uri)) uri = String.format(PNG_URI, id)
            name = Parser.Emote.parseName(urlRef)
        } else {
            uri = urlRef
            val matcher = Pattern.compile("(?=(\\w+)\\.\\w{3,4}(?:\\?.*)?$).+").matcher(uri!!)
            Check.check(matcher.find()) { ReplyError("Malformed URL!") }
            name = matcher.group(1)
        }
        val url: URL = try {
            URL(uri)
        } catch (e: MalformedURLException) {
            throw ReplyError("Malformed URL!")
        }
        try {
            val icon = Icon.from(url.openStream())
            val emote = event.guild.createEmote((if (args.size > 1) args[1] else name)!!, icon).complete()
            event.reply("Added Emote", "Added ${emote.asMention} with name `${emote.name}`").queue()
        } catch (e: IOException) {
            throw UnexpectedError()
        } catch (e: ErrorResponseException) {
            throw ReplyError(e.meaning)
        }
    }

    companion object {
        private const val GIF_URI = "https://cdn.discordapp.com/emojis/%s.gif"
        private const val PNG_URI = "https://cdn.discordapp.com/emojis/%s.png"
        fun exists(URLName: String?): Boolean {
            return try {
                val con = URL(URLName).openConnection() as HttpURLConnection
                con.requestMethod = "HEAD"
                con.responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                false
            }
        }
    }
}


@SubCommand(Emotes::class)
@CommandOptions(
    name = "export",
    description = "Export all the server's emotes",
    perm = CommandPerm.BOT_MANAGER
)
@Checks.Permissions.Guild(
    Permission.MANAGE_EMOTES
)
class Export : GuildCommand() {
    override fun run(event: GuildCommandEvent) {
        val emotes = event.guild.emotes
        event.addCheckmark()
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        emotes.forEach {
            run {
                val entry = ZipEntry("${it.name}.${it.id}.${if (it.isAnimated) "gif" else "png"}")
                zos.putNextEntry(entry)
                zos.write(download(it.imageUrl))
                zos.closeEntry()
            }
        }
        zos.close()
        event.channel.sendFile(baos.toByteArray(), "emotes_${event.guild.name}_${LocalDateTime.now()}.zip").queue()
    }

    private fun download(url: String): ByteArray {
        val client = HttpClientBuilder.create().build()
        val request = HttpGet(url)
        val response = client.execute(request)
        return response.entity.content.readAllBytes()
    }
}