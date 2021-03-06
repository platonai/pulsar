package ai.platon.pulsar.crawl.common.collect

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.PriorityDataCollector
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.sql.ResultSetFormatter
import ai.platon.pulsar.ql.ResultSets
import org.h2.tools.SimpleResultSet
import java.time.Duration
import java.time.Instant

abstract class PriorityDataCollectorFormatterBase<T> {
    fun newResultSet(): SimpleResultSet {
        return ResultSets.newSimpleResultSet(
            "name", "priority", "pName",
            "collected", "cd/s", "collect", "c/s", "time",
            "size", "estSize", "firstCollect", "lastCollect"
        )
    }

    fun addRow(c: PriorityDataCollector<T>, rs: SimpleResultSet) {
        val dtFormatter = "MM-dd HH:mm:ss"
        val firstCollectTime = c.firstCollectTime.atZone(DateTimes.zoneId).toLocalDateTime()
        val lastCollectedTime = c.lastCollectedTime.atZone(DateTimes.zoneId).toLocalDateTime()
        val elapsedTime = if (c.lastCollectedTime > c.firstCollectTime)
            Duration.between(c.firstCollectTime, c.lastCollectedTime) else Duration.ZERO
        val elapsedSeconds = elapsedTime.seconds.coerceAtLeast(1)
        val priorityName = Priority13.valueOfOrNull(c.priority)?.name ?: ""

        rs.addRow(
            c.name, c.priority, priorityName,
            c.collectedCount,
            String.format("%.2f", 1.0 * c.collectedCount / elapsedSeconds),
            c.collectCount,
            String.format("%.2f", 1.0 * c.collectCount / elapsedSeconds),
            elapsedTime.readable(),
            c.size, c.estimatedSize,
            DateTimes.format(firstCollectTime, dtFormatter),
            DateTimes.format(lastCollectedTime, dtFormatter)
        )
    }
}

class PriorityDataCollectorFormatter<T>(
    val collector: PriorityDataCollector<T>,
) : PriorityDataCollectorFormatterBase<T>() {
    override fun toString(): String {
        val rs = newResultSet()
        addRow(collector, rs)
        return ResultSetFormatter(rs, withHeader = true).toString()
    }
}

class PriorityDataCollectorsFormatter<T>(
    val collectors: List<PriorityDataCollector<T>>,
) : PriorityDataCollectorFormatterBase<T>() {
    fun abstract(): String {
        val firstCollectTime = collectors.filter { it.firstCollectTime > Instant.EPOCH }
            .minOfOrNull { it.firstCollectTime }
        val lastCollectedTime = collectors.maxOf { it.lastCollectedTime }
        val collectedCount = collectors.sumBy { it.collectedCount }
        val collectCount = collectors.sumBy { it.collectCount }
        val size = collectors.sumBy { it.size }
        val estimatedSize = collectors.sumBy { it.estimatedSize }
        val elapsedTime = DateTimes.elapsedTime()
        val elapsedSeconds = elapsedTime.seconds

        return String.format("Total collected %s/%s/%s/%s in %s, remaining %s/%s, collect time: %s -> %s",
            collectedCount,
            String.format("%.2f", 1.0 * collectedCount / elapsedSeconds),
            collectCount,
            String.format("%.2f", 1.0 * collectCount / elapsedSeconds),
            elapsedTime.readable(),
            size, estimatedSize,
            firstCollectTime, lastCollectedTime
        )
    }

    override fun toString(): String {
        val rs = newResultSet()
        collectors.forEach { addRow(it, rs) }
        return ResultSetFormatter(rs, withHeader = true).toString()
    }
}
