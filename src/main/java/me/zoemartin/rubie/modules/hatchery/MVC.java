package me.zoemartin.rubie.modules.hatchery;

import me.zoemartin.rubie.core.CommandPerm;
import me.zoemartin.rubie.core.interfaces.GuildCommand;
import me.zoemartin.rubie.core.util.Parser;
import net.dv8tion.jda.api.entities.*;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class MVC implements GuildCommand {
    @Override
    public void run(Member user, TextChannel channel, List<String> args, Message original, String invoked) {
        Guild g = original.getGuild();
        if (!Hatchery.isHatchery(g)) return;

        g.loadMembers().get();

        int abstains = 0;
        if (!args.isEmpty()) abstains = Parser.Int.parse(args.get(0));

        Role role = g.getRolesByName("mvc", true).get(0);


        int count = g.getMembersWithRoles(role).size();
        int majority = (count - abstains) / 2 + 1;

        embedReply(original, channel, "MVC Majority", "The majority of %d with %d abstains is %d",
            count, abstains, majority).queue();
    }

    @NotNull
    @Override
    public String name() {
        return "mvc";
    }

    @NotNull
    @Override
    public CommandPerm commandPerm() {
        return CommandPerm.BOT_MANAGER;
    }

    @NotNull
    @Override
    public String usage() {
        return "[abstains]";
    }

    @NotNull
    @Override
    public String description() {
        return "Returns the MVC Count";
    }
}