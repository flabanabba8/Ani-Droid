package dev.anidroid.server

import dev.anidroid.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class Store(path: Path) : AutoCloseable {
    private val db = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    init {
        db.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=5000")
            s.execute("""CREATE TABLE IF NOT EXISTS titles(provider TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, search_name TEXT NOT NULL,
                series INTEGER NOT NULL, poster TEXT NOT NULL, info TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                PRIMARY KEY(provider,id))""")
            s.execute("CREATE TABLE IF NOT EXISTS enrichment(provider TEXT NOT NULL,id TEXT NOT NULL,attempted INTEGER NOT NULL,PRIMARY KEY(provider,id))")
            s.execute("CREATE TABLE IF NOT EXISTS genres(provider TEXT NOT NULL,id TEXT NOT NULL,genre TEXT NOT NULL,PRIMARY KEY(provider,id,genre))")
            s.execute("CREATE INDEX IF NOT EXISTS genre_lookup ON genres(genre,provider,id)")
            s.execute("CREATE INDEX IF NOT EXISTS names ON titles(search_name,provider,id)")
            s.execute("""CREATE TABLE IF NOT EXISTS feeds(name TEXT PRIMARY KEY,provider TEXT NOT NULL,feed TEXT NOT NULL,page INTEGER NOT NULL DEFAULT 1,
                total_pages INTEGER NOT NULL DEFAULT 0,last_page INTEGER NOT NULL DEFAULT 0,last_fingerprint TEXT NOT NULL DEFAULT '',
                cycle_start INTEGER NOT NULL DEFAULT 0,last_success INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL DEFAULT 0,
                next_run INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',empty_streak INTEGER NOT NULL DEFAULT 0)""")
            s.execute("CREATE TABLE IF NOT EXISTS details(provider TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(provider,id))")
            // MovieBox was explicitly retired. Anime rows and scan checkpoints stay intact.
            listOf("genres","enrichment","details","feeds","titles").forEach { table -> s.execute("DELETE FROM $table WHERE provider='movie'") }
            listOf(Triple("Anime A–Z", "ani", "az"), Triple("Luffy · Movies (English)", "luffy", "movie"), Triple("Luffy · Series (English)", "luffy", "tv")).forEach { (name,p,f) ->
                db.prepareStatement("INSERT OR IGNORE INTO feeds(name,provider,feed) VALUES(?,?,?)").use { q -> q.setString(1,name);q.setString(2,p);q.setString(3,f);q.executeUpdate() }
            }
        }
    }
    // ponytail: serialized, short SQLite operations suffice for a personal catalog.
    @Synchronized fun upsert(titles: List<Title>, now: Long = System.currentTimeMillis()) {
        db.prepareStatement("""INSERT INTO titles VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(provider,id) DO UPDATE SET
            name=excluded.name,search_name=excluded.search_name,series=excluded.series,poster=excluded.poster,info=excluded.info,last_seen=excluded.last_seen""").use { q ->
            titles.forEach { t ->
                q.setString(1,t.provider);q.setString(2,t.id);q.setString(3,t.name);q.setString(4,t.name.lowercase(java.util.Locale.ROOT))
                q.setInt(5,if(t.series) 1 else 0);q.setString(6,t.poster);q.setString(7,t.info);q.setLong(8,now);q.setLong(9,now);q.addBatch()
            }; q.executeBatch()
        }
        db.prepareStatement("INSERT OR IGNORE INTO genres(provider,id,genre) VALUES(?,?,?)").use { q ->
            titles.forEach { t -> t.genres.forEach { genre -> q.setString(1,t.provider);q.setString(2,t.id);q.setString(3,genre);q.addBatch() } };q.executeBatch()
        }
    }
    private fun genresFor(provider: String,id: String): List<String> = db.prepareStatement("SELECT genre FROM genres WHERE provider=? AND id=? ORDER BY genre").use { q ->
        q.setString(1,provider);q.setString(2,id);q.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1)) } }
    }
    @Synchronized fun catalog(query: String, provider: String, kind: String, page: Int, sort: String, genre: String = ""): JSONObject {
        require(genre.length <= 80 && query.length <= 200 && provider in listOf("all","ani","luffy") && kind in listOf("all","movie","series") && page in 1..10000 && sort in listOf("name","recent"))
        val clauses = mutableListOf<String>();val values = mutableListOf<String>()
        if(query.isNotBlank()) { clauses += "search_name LIKE ? ESCAPE '\\'"; values += "%" + query.trim().lowercase(java.util.Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%" }
        if(provider != "all") { clauses += "provider=?";values += provider }
        if(kind != "all") { clauses += "series=?";values += if(kind == "series") "1" else "0" }
        val contextWhere=if(clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        val contextValues=values.toList()
        if(genre == "__untagged__") clauses += "NOT EXISTS(SELECT 1 FROM genres g WHERE g.provider=titles.provider AND g.id=titles.id)"
        else if(genre.isNotBlank()) { clauses += "EXISTS(SELECT 1 FROM genres g WHERE g.provider=titles.provider AND g.id=titles.id AND g.genre=?)";values += genre }
        val where = if(clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        val total = db.prepareStatement("SELECT count(*) FROM titles$where").use { q -> values.forEachIndexed { i,v -> q.setString(i+1,v) }; q.executeQuery().use { it.next();it.getInt(1) } }
        val order = if(sort == "name") "search_name,provider,id" else "first_seen DESC,search_name,provider,id"
        val items = db.prepareStatement("SELECT * FROM titles$where ORDER BY $order LIMIT 40 OFFSET ?").use { q ->
            values.forEachIndexed { i,v -> q.setString(i+1,v) };q.setInt(values.size+1,(page-1)*40)
            q.executeQuery().use { r -> JSONArray().apply { while(r.next()) put(Title(r.getString("provider"),r.getString("id"),r.getString("name"),r.getString("poster"),r.getString("info"),r.getInt("series") == 1,r.getLong("last_seen"),genresFor(r.getString("provider"),r.getString("id"))).toJson()) } }
        }
        val genres=db.prepareStatement("SELECT g.genre,count(*) FROM titles JOIN genres g ON g.provider=titles.provider AND g.id=titles.id" + contextWhere.replace("provider=?","titles.provider=?") + " GROUP BY g.genre ORDER BY g.genre").use { q ->
            contextValues.forEachIndexed { i,v -> q.setString(i+1,v) };q.executeQuery().use { r -> JSONArray().apply { while(r.next()) put(JSONObject().put("name",r.getString(1)).put("count",r.getInt(2))) } }
        }
        val coverage=db.prepareStatement("SELECT count(*),coalesce(sum(EXISTS(SELECT 1 FROM genres g WHERE g.provider=titles.provider AND g.id=titles.id)),0) FROM titles$contextWhere").use { q ->
            contextValues.forEachIndexed { i,v -> q.setString(i+1,v) };q.executeQuery().use { r -> r.next();JSONObject().put("total",r.getInt(1)).put("tagged",r.getInt(2)).put("untagged",r.getInt(1)-r.getInt(2)) }
        }
        return JSONObject().put("coverage",coverage).put("genres",genres).put("items",items).put("page",page).put("total",total).put("hasMore",page*40 < total).put("status",status())
    }
    @Synchronized fun find(provider: String,id: String): Title? = db.prepareStatement("SELECT * FROM titles WHERE provider=? AND id=?").use { q ->
        q.setString(1,provider);q.setString(2,id);q.executeQuery().use { r -> if(!r.next()) null else Title(provider,id,r.getString("name"),r.getString("poster"),r.getString("info"),r.getInt("series")==1,r.getLong("last_seen"),genresFor(provider,id)) }
    }
    @Synchronized fun status(): JSONObject {
        val sources = db.createStatement().use { s -> s.executeQuery("SELECT provider,count(*),max(last_seen) FROM titles GROUP BY provider").use { r -> JSONArray().apply { while(r.next()) put(JSONObject().put("provider",r.getString(1)).put("count",r.getInt(2)).put("updatedAt",r.getLong(3))) } } }
        val feeds = db.createStatement().use { s -> s.executeQuery("SELECT * FROM feeds ORDER BY name").use { r -> JSONArray().apply { while(r.next()) put(JSONObject().put("name",r.getString("name")).put("page",r.getInt("last_page")).put("totalPages",r.getInt("total_pages")).put("lastSuccess",r.getLong("last_success")).put("completedAt",r.getLong("completed")).put("error",r.getString("error")).put("nextRun",r.getLong("next_run"))) } } }
        return JSONObject().put("sources",sources).put("feeds",feeds).put("generatedAt",System.currentTimeMillis())
    }
    @Synchronized fun due(now: Long): JSONObject? = db.prepareStatement("SELECT * FROM feeds WHERE next_run<=? ORDER BY CASE WHEN feed NOT LIKE 'genres/%' AND last_success < ? THEN 0 ELSE 1 END,last_success,CASE WHEN feed IN ('genres/action','genres/adventure','genres/comedy','genres/drama','genres/fantasy','genres/horror','genres/mystery','genres/romance','genres/sci-fi','genres/slice-of-life','genres/sports','genres/thriller') THEN 0 ELSE 1 END,name LIMIT 1").use { q ->
        q.setLong(1,now);q.setLong(2,now-30000);q.executeQuery().use { r -> if(!r.next()) null else JSONObject().apply { listOf("name","provider","feed","last_fingerprint").forEach { put(it,r.getString(it)) };listOf("page","cycle_start","empty_streak").forEach { put(it,r.getLong(it)) } } }
    }
    @Synchronized fun recordPage(feed: JSONObject, batch: BrowsePage, now: Long) {
        val page = feed.getInt("page"); val fingerprint = Providers.md5(batch.titles.map { it.id }.sorted().joinToString("|").toByteArray())
        val repeated = page > 1 && fingerprint == feed.getString("last_fingerprint")
        val newTitles=db.prepareStatement("SELECT 1 FROM titles WHERE provider=? AND id=?").use { q ->
            batch.titles.count { title -> q.setString(1,title.provider);q.setString(2,title.id);q.executeQuery().use { !it.next() } }
        }
        val emptyStreak = if(newTitles==0) feed.optInt("empty_streak")+1 else 0
        // Anime has a finite paginated directory. Missing genre metadata must never
        // block discovery, and a repeated page before the end is an error, not completion.
        val movie=feed.getString("provider")=="luffy"
        check(movie || !repeated || !batch.hasNext) { "Anime directory repeated a page before its end" }
        check(page<10000 || !batch.hasNext) { "Provider pagination exceeded the safety limit" }
        val done = !batch.hasNext || (movie && (repeated || emptyStreak>=3))
        db.autoCommit = false
        try {
            upsert(batch.titles,now)
            db.prepareStatement("INSERT OR IGNORE INTO feeds(name,provider,feed) VALUES(?, 'ani', ?)").use { q ->
                batch.genreFeeds.forEach { genre -> q.setString(1,"Anime · ${genre.label}");q.setString(2,genre.path);q.addBatch() };q.executeBatch()
            }
            db.prepareStatement("UPDATE feeds SET page=?,total_pages=?,last_page=?,last_fingerprint=?,cycle_start=?,last_success=?,completed=?,next_run=?,error='',empty_streak=? WHERE name=?").use { q ->
                q.setInt(1,if(done) 1 else page+1);q.setInt(2,batch.totalPages);q.setInt(3,page);q.setString(4,if(done) "" else fingerprint)
                q.setLong(5,if(page==1) now else feed.getLong("cycle_start"));q.setLong(6,now);q.setLong(7,if(done) now else 0)
                q.setLong(8,if(done) now+24*60*60*1000L else now+if(movie) 300000 else 10000);q.setInt(9,emptyStreak);q.setString(10,feed.getString("name"));q.executeUpdate()
            };db.commit()
        } catch(e: Exception) { db.rollback();throw e } finally { db.autoCommit=true }
    }
    @Synchronized fun fail(name: String,now: Long) {
        db.prepareStatement("UPDATE feeds SET error=?,next_run=? WHERE name=?").use { q -> q.setString(1,"Provider unavailable; retrying in 15 minutes. Saved listings remain available.");q.setLong(2,now+900000);q.setString(3,name);q.executeUpdate() }
    }
    @Synchronized fun nextEnrichment(now: Long,provider: String = "luffy"): Title? {
        val key=db.prepareStatement("SELECT t.provider,t.id FROM titles t LEFT JOIN enrichment e ON e.provider=t.provider AND e.id=t.id WHERE t.provider=? AND (e.attempted IS NULL OR e.attempted<?) ORDER BY CASE WHEN NOT EXISTS(SELECT 1 FROM genres g WHERE g.provider=t.provider AND g.id=t.id) THEN 0 ELSE 1 END,t.first_seen ASC,t.id LIMIT 1").use { q ->
            q.setString(1,provider);q.setLong(2,now-86400000);q.executeQuery().use { r -> if(r.next()) r.getString(1) to r.getString(2) else null }
        }
        return key?.let { find(it.first,it.second)?.also { title -> markEnrichment(title,now) } }
    }
    @Synchronized fun markEnrichment(title: Title,now: Long) {
        db.prepareStatement("INSERT OR REPLACE INTO enrichment VALUES(?,?,?)").use { q -> q.setString(1,title.provider);q.setString(2,title.id);q.setLong(3,now);q.executeUpdate() }
    }
    @Synchronized fun cachedDetails(provider: String,id: String): Pair<Details,Long>? = db.prepareStatement("SELECT json,updated FROM details WHERE provider=? AND id=?").use { q ->
        q.setString(1,provider);q.setString(2,id);q.executeQuery().use { r -> if(!r.next()) null else detailsFromJson(JSONObject(r.getString(1))) to r.getLong(2) }
    }
    @Synchronized fun saveDetails(value: Details,now: Long) {
        upsert(listOf(value.title),now)
        db.prepareStatement("INSERT OR REPLACE INTO details VALUES(?,?,?,?)").use { q -> q.setString(1,value.title.provider);q.setString(2,value.title.id);q.setString(3,value.toJson().toString());q.setLong(4,now);q.executeUpdate() }
    }
    @Synchronized override fun close() = db.close()
}
