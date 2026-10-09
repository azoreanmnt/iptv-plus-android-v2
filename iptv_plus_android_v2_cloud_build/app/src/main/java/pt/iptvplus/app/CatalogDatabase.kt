package pt.iptvplus.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local persistent catalog cache. All database calls must run on Dispatchers.IO. */
class CatalogDatabase(context: Context) : SQLiteOpenHelper(context, "iptv_plus_catalog.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE catalog (
            kind TEXT NOT NULL,
            item_id TEXT NOT NULL,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            group_name TEXT NOT NULL,
            logo TEXT NOT NULL,
            extension TEXT NOT NULL,
            PRIMARY KEY(kind, item_id, url)
        )""".trimIndent())
        db.execSQL("CREATE INDEX catalog_kind_group ON catalog(kind, group_name)")
        db.execSQL("CREATE INDEX catalog_kind_name ON catalog(kind, name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceKind(kind: String, items: List<TvItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("catalog", "kind = ?", arrayOf(kind))
            // Insert in batches inside one transaction; SQLite stays responsive for large catalogs.
            items.chunked(500).forEach { batch ->
                batch.forEach { item ->
                    val values = ContentValues().apply {
                        put("kind", kind)
                        put("item_id", item.id.ifBlank { item.url })
                        put("name", item.name)
                        put("url", item.url)
                        put("group_name", item.group.ifBlank { "Geral" })
                        put("logo", item.logo)
                        put("extension", item.extension)
                    }
                    db.insertWithOnConflict("catalog", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getItems(kind: String): List<TvItem> {
        val out = ArrayList<TvItem>()
        readableDatabase.query(
            "catalog", arrayOf("name", "url", "group_name", "logo", "kind", "item_id", "extension"),
            "kind = ?", arrayOf(kind), null, null, "name COLLATE NOCASE"
        ).use { c ->
            while (c.moveToNext()) out.add(TvItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)))
        }
        return out
    }

    fun count(kind: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM catalog WHERE kind = ?", arrayOf(kind)).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}

