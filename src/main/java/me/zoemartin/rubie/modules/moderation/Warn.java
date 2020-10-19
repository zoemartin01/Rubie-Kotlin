package me.zoemartin.rubie.modules.moderation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.zoemartin.rubie.Bot;
import me.zoemartin.rubie.core.CommandPerm;
import me.zoemartin.rubie.core.exceptions.*;
import me.zoemartin.rubie.core.interfaces.Command;
import me.zoemartin.rubie.core.interfaces.GuildCommand;
import me.zoemartin.rubie.modules.pagedEmbeds.PageListener;
import me.zoemartin.rubie.modules.pagedEmbeds.PagedEmbed;
import me.zoemartin.rubie.core.util.*;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.*;
import org.hibernate.Session;
import org.jetbrains.annotations.NotNull;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import javax.persistence.criteria.*;
import java.io.*;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Warn implements GuildCommand {
    @Override
    public @NotNull Set<Command> subCommands() {
        return Set.of(new list(), new Remove(), new BulkImportFile());
    }

    @Override
    public @NotNull String name() {
        return "warn";
    }

    @Override
    public void run(Member user, TextChannel channel, List<String> args, Message original, String invoked) {
        Check.check(args.size() > 1 && Parser.User.isParsable(args.get(0)), CommandArgumentException::new);
        String userId = args.get(0);

        User u = Bot.getJDA().getUserById(Parser.User.parse(userId));
        Check.notNull(u, UserNotFoundException::new);

        String reason = lastArg(1, args, original);

        ModLogEntity warnEntity = new ModLogEntity(
            original.getGuild().getId(), u.getId(), user.getId(), reason, original.getTimeCreated().toInstant().toEpochMilli(),
            ModLogEntity.ModLogType.WARN);

        DatabaseUtil.saveObject(warnEntity);
        EmbedBuilder eb = new EmbedBuilder()
                              .setTitle("Warning added")
                              .setAuthor(String.format("%s / %s", u.getAsTag(), u.getId()), null, u.getEffectiveAvatarUrl())
                              .setDescription(String.format("Successfully warned %s for:\n\n%s", u.getAsMention(), warnEntity.getReason()));

        if (!u.isBot())
            u.openPrivateChannel().complete()
                .sendMessageFormat("You have received a warning from a Moderator on `%s`. \n**Reason**:\n\n%s",
                    original.getGuild().getName(), warnEntity.getReason()).queue();

        channel.sendMessage(eb.build()).queue();
    }

    @Override
    public @NotNull CommandPerm commandPerm() {
        return CommandPerm.BOT_MODERATOR;
    }

    @Override
    public @NotNull String usage() {
        return "<user> <reason>";
    }

    @Override
    public @NotNull String description() {
        return "Warn a user";
    }

    private static class list implements GuildCommand {

        @Override
        public @NotNull String name() {
            return "list";
        }

        @Override
        public void run(Member user, TextChannel channel, List<String> args, Message original, String invoked) {
            String userId = args.get(0);
            Check.check(args.size() == 1 && Parser.User.isParsable(userId), CommandArgumentException::new);

            User u = Bot.getJDA().getUserById(Parser.User.parse(userId));
            Check.notNull(u, UserNotFoundException::new);

            Session s = DatabaseUtil.getSessionFactory().openSession();
            CriteriaBuilder cb = s.getCriteriaBuilder();

            CriteriaQuery<ModLogEntity> q = cb.createQuery(ModLogEntity.class);
            Root<ModLogEntity> r = q.from(ModLogEntity.class);
            List<ModLogEntity> warns = s.createQuery(q.select(r).where(
                cb.equal(r.get("type"), ModLogEntity.ModLogType.WARN.raw()),
                cb.equal(r.get("guild_id"), original.getGuild().getId()),
                cb.equal(r.get("user_id"), userId))).getResultList();

            List<MessageEmbed> pages = EmbedUtil.pagedFieldEmbed(
                new EmbedBuilder()
                    .setAuthor(String.format("%s / %s", u.getAsTag(), u.getId()),
                        null, u.getEffectiveAvatarUrl())
                    .setTitle("Warnings (" + warns.size() + ")").build(), warns.stream().map(e -> {
                    User moderator = Bot.getJDA().getUserById(e.getModerator_id());
                    return new MessageEmbed.Field("Warn ID: `" + e.getUuid() + "`",
                        String.format("**Responsible Moderator**: %s\n\n" +
                                          "**On**: %s\n\n" +
                                          "**Reason**: %s",
                            moderator != null ? moderator.getAsMention() : e.getModerator_id(),
                            Timestamp.valueOf(Instant.ofEpochSecond(e.getTimestamp())
                                                  .atOffset(ZoneOffset.UTC).toLocalDateTime()),
                            e.getReason()), true);
                }).collect(Collectors.toList())
            );

            PageListener.add(new PagedEmbed(pages, channel, user.getUser()));
        }

        @Override
        public @NotNull CommandPerm commandPerm() {
            return CommandPerm.BOT_MODERATOR;
        }

        @Override
        public @NotNull String usage() {
            return "<user>";
        }

        @Override
        public @NotNull String description() {
            return "Lists a users warns";
        }
    }

    private static class Remove implements GuildCommand {
        @Override
        public @NotNull String name() {
            return "remove";
        }

        @Override
        public void run(Member user, TextChannel channel, List<String> args, Message original, String invoked) {
            Check.check(args.size() == 1, CommandArgumentException::new);

            UUID uuid = UUID.fromString(args.get(0));

            Session s = DatabaseUtil.getSessionFactory().openSession();
            CriteriaBuilder cb = s.getCriteriaBuilder();

            CriteriaQuery<ModLogEntity> q = cb.createQuery(ModLogEntity.class);
            Root<ModLogEntity> r = q.from(ModLogEntity.class);
            List<ModLogEntity> warns = s.createQuery(q.select(r).where(
                cb.equal(r.get("type"), ModLogEntity.ModLogType.WARN.raw()),
                cb.equal(r.get("guild_id"), original.getGuild().getId()),
                cb.equal(r.get("uuid"), uuid))).getResultList();

            ModLogEntity warn = warns.isEmpty() ? null : warns.get(0);
            Check.notNull(warn, () -> new ReplyError("No warning with the ID `%s`", uuid));

            User u = Bot.getJDA().getUserById(warn.getUser_id());

            DatabaseUtil.deleteObject(warn);

            EmbedBuilder eb = new EmbedBuilder()
                                  .setTitle("Warning removed")
                                  .setDescription(String.format("Successfully removed warn `%s`", uuid));

            if (u != null)
                eb.setAuthor(String.format("%s / %s", u.getAsTag(), u.getId()), null, u.getEffectiveAvatarUrl());

            channel.sendMessage(eb.build()).queue();
        }

        @Override
        public @NotNull CommandPerm commandPerm() {
            return CommandPerm.BOT_MANAGER;
        }

        @Override
        public @NotNull String usage() {
            return "<uuid>";
        }

        @Override
        public @NotNull String description() {
            return "Remove a warning";
        }
    }

    private static class BulkImportFile implements GuildCommand {
        private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

        @Override
        public @NotNull String name() {
            return "import";
        }

        @Override
        public void run(Member user, TextChannel channel, List<String> args, Message original, String invoked) {
            Check.check(args.isEmpty(), CommandArgumentException::new);
            Check.check(original.getAttachments().size() == 1, CommandArgumentException::new);
            Message m = channel.sendMessage("Okay... this might take a while").complete();

            InputStreamReader ir;
            BufferedReader br;
            try {
                ir = new InputStreamReader(original.getAttachments().get(0).retrieveInputStream().get(1, TimeUnit.MINUTES));
                br = new BufferedReader(ir);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new UnexpectedError(e);
            }

            Session s = DatabaseUtil.getSessionFactory().openSession();
            CriteriaBuilder cb = s.getCriteriaBuilder();

            CriteriaQuery<ModLogEntity> q = cb.createQuery(ModLogEntity.class);
            Root<ModLogEntity> r = q.from(ModLogEntity.class);
            List<ModLogEntity> existing = s.createQuery(q.select(r).where(
                cb.equal(r.get("guild_id"), original.getGuild().getId()))).getResultList();

            Type listType = new TypeToken<ArrayList<ModLogs.ModLogEntry>>() {
            }.getType();
            List<ModLogs.ModLogEntry> toImport = new Gson().fromJson(br, listType);

            String guildId = original.getGuild().getId();
            List<ModLogEntity> modlogs = toImport.stream().filter(e -> e.getAction().equals("warn"))
                                             .map(e ->
                                                     new ModLogEntity(guildId, e.getOffender_id(), e.getModerator_id(), e.getReason(),
                                                         DateTime.parse(e.getTimestamp(), TIME_FORMATTER).getMillis(), ModLogEntity.ModLogType.WARN)

                                             ).filter(e -> existing.stream().noneMatch(e::equals))
                                             .collect(Collectors.toList());

            modlogs.forEach(DatabaseUtil::saveObject);
            Set<String> users = modlogs.stream().map(ModLogEntity::getUser_id)
                                    .collect(Collectors.toCollection(HashSet::new));

            EmbedBuilder eb = new EmbedBuilder().setTitle("Bulk Warn Import");
            eb.setDescription("Imported warns:\n" + String.join("\n", users));
            m.delete().complete();
            channel.sendMessage(eb.build()).queue();
        }

        @Override
        public @NotNull CommandPerm commandPerm() {
            return CommandPerm.BOT_ADMIN;
        }

        @Override
        public @NotNull String description() {
            return "Bulk Import Warns. These warns are added silently. Attach a text file with one Line for each warn.";
        }
    }
}
