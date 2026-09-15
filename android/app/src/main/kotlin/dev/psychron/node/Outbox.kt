package dev.psychron.node

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Messages waiting for the broker, on disk.
 *
 * The in-memory queue this replaces held an hour and died with the process: a phone
 * that killed the app to free memory, a reboot, an update installed overnight, and
 * the backlog was gone. On disk it survives all of them, and a day of windows is
 * forty megabytes, which a phone does not notice.
 *
 * Stored already serialised, with the wall-clock time each was created. Whether a
 * message was replayed is decided when it finally leaves, from how long it waited —
 * which is also why the wall clock and not the monotonic one: a message can outlive
 * the boot it was written in.
 *
 * SQLite from the platform, not a library. One writer (the sampling thread), one
 * reader and deleter (the link), and the database's own locking between them.
 */
class Outbox(context: Context, private val capacity: Int = DAY_OF_WINDOWS) {

    data class Message(val id: Long, val topic: String, val json: String, val createdMs: Long)

    private val helper = object : SQLiteOpenHelper(context, "outbox.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            // Write-ahead logging: an insert every two seconds must not wait for a
            // burst of deletes on the other thread, nor the other way round.
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "topic TEXT NOT NULL, payload TEXT NOT NULL, created_ms INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }

    private val db: SQLiteDatabase = helper.writableDatabase
    private val size = AtomicInteger(countRows())

    val count: Int get() = size.get()

    /**
     * Appends a message. When full, the oldest go first, and how many is returned so
     * the loss is counted where it can be seen: a queue that must drop something
     * should drop what is least likely to still matter, and say so.
     */
    fun add(topic: String, json: String, createdMs: Long): Int {
        db.insert("outbox", null, ContentValues().apply {
            put("topic", topic)
            put("payload", json)
            put("created_ms", createdMs)
        })
        if (size.incrementAndGet() <= capacity) return 0
        val excess = size.get() - capacity
        db.execSQL("DELETE FROM outbox WHERE id IN (SELECT id FROM outbox ORDER BY id LIMIT $excess)")
        size.set(countRows())
        return excess
    }

    /** The oldest messages, in the order they were written. */
    fun oldest(limit: Int): List<Message> =
        db.rawQuery("SELECT id, topic, payload, created_ms FROM outbox ORDER BY id LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Message(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    /** Wall-clock creation time of the oldest message, or null when empty. */
    fun oldestCreatedMs(): Long? =
        db.rawQuery("SELECT created_ms FROM outbox ORDER BY id LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    /** Only after the broker has acknowledged it: this is the at-least-once line. */
    fun remove(id: Long) {
        if (db.delete("outbox", "id = ?", arrayOf(id.toString())) > 0) size.decrementAndGet()
    }

    fun close() = helper.close()

    private fun countRows(): Int =
        db.rawQuery("SELECT count(*) FROM outbox", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    companion object {
        /** A day at one window every two seconds, plus room for the events among them. */
        const val DAY_OF_WINDOWS = 45_000
    }
}
