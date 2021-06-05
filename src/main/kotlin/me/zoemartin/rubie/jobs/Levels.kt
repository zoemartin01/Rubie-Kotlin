package me.zoemartin.rubie.jobs

import me.zoemartin.rubie.Bot.jda
import me.zoemartin.rubie.core.Job
import me.zoemartin.rubie.core.Job.CommonKeys
import me.zoemartin.rubie.core.annotations.JobProcessor
import me.zoemartin.rubie.core.interfaces.JobProcessorInterface
import me.zoemartin.rubie.core.util.DatabaseUtil
import me.zoemartin.rubie.managers.Levels.getConfig
import java.util.function.Consumer

@JobProcessor
class TempBlockLevels : JobProcessorInterface {
    override fun uuid(): String {
        return "6b54f418-4937-4cf2-af70-d1792fef1360"
    }

    override fun process(): Consumer<Job> {
        return Consumer { job: Job ->
            val settings = job.settings
            if (!settings.keys.containsAll(setOf(CommonKeys.GUILD, CommonKeys.USER))) return@Consumer
            val g = jda.getGuildById(settings[CommonKeys.GUILD]!!) ?: return@Consumer
            val conf = getConfig(g)
            conf.unblocksUser(settings[CommonKeys.USER])
            DatabaseUtil.updateObject(conf)
        }
    }
}
