package com.mewcode.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQLite FTS5 store with deterministic add/merge/supersede behavior. */
public final class SqliteMemoryStore {
    public record Entry(String id, String type, String name, String description, String content, String state) {}
    private final String jdbcUrl;
    public SqliteMemoryStore(Path database) {
        try { Files.createDirectories(database.toAbsolutePath().normalize().getParent()); }
        catch (Exception e) { throw new IllegalStateException("Cannot create memory directory", e); }
        this.jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        initialize();
    }
    private Connection open() throws Exception { return DriverManager.getConnection(jdbcUrl); }
    private void initialize() {
        try (Connection c=open(); var s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS memory(id TEXT PRIMARY KEY,type TEXT NOT NULL,name TEXT NOT NULL,description TEXT NOT NULL,content TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'ACTIVE',supersedes_id TEXT,content_hash TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS memory_active_name ON memory(type,name) WHERE state='ACTIVE'");
            s.executeUpdate("CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(id UNINDEXED,name,description,content)");
        } catch (Exception e) { throw new IllegalStateException("Cannot initialize SQLite memory", e); }
    }
    public synchronized void remember(String type,String name,String description,String content) {
        String hash=sha256(content); String now=Instant.now().toString();
        try (Connection c=open()) {
            c.setAutoCommit(false);
            String existingId=null, existingHash=null;
            try (PreparedStatement q=c.prepareStatement("SELECT id,content_hash FROM memory WHERE type=? AND name=? AND state='ACTIVE'")) {
                q.setString(1,type);q.setString(2,name);try(var rs=q.executeQuery()){if(rs.next()){existingId=rs.getString(1);existingHash=rs.getString(2);}}
            }
            if (hash.equals(existingHash)) { c.rollback(); return; }
            if (existingId != null) {
                try(PreparedStatement u=c.prepareStatement("UPDATE memory SET state='SUPERSEDED',updated_at=? WHERE id=?")){u.setString(1,now);u.setString(2,existingId);u.executeUpdate();}
                try(PreparedStatement d=c.prepareStatement("DELETE FROM memory_fts WHERE id=?")){d.setString(1,existingId);d.executeUpdate();}
            }
            String id=UUID.randomUUID().toString();
            try(PreparedStatement i=c.prepareStatement("INSERT INTO memory VALUES(?,?,?,?,?,'ACTIVE',?,?,?,?)")){
                i.setString(1,id);i.setString(2,type);i.setString(3,name);i.setString(4,description);i.setString(5,content);i.setString(6,existingId);i.setString(7,hash);i.setString(8,now);i.setString(9,now);i.executeUpdate();
            }
            try(PreparedStatement i=c.prepareStatement("INSERT INTO memory_fts(id,name,description,content) VALUES(?,?,?,?)")){i.setString(1,id);i.setString(2,name);i.setString(3,description);i.setString(4,content);i.executeUpdate();}
            c.commit();
        } catch(Exception e){throw new IllegalStateException("Cannot store memory",e);}
    }
    public List<Entry> list() { return query("SELECT id,type,name,description,content,state FROM memory WHERE state='ACTIVE' ORDER BY updated_at DESC",null,100); }
    public List<Entry> search(String text,int limit) {
        if(text==null||text.isBlank()) return List.of();
        return query("SELECT m.id,m.type,m.name,m.description,m.content,m.state FROM memory_fts f JOIN memory m ON m.id=f.id WHERE memory_fts MATCH ? AND m.state='ACTIVE' ORDER BY bm25(memory_fts) LIMIT ?",text,limit);
    }
    private List<Entry> query(String sql,String value,int limit) {
        List<Entry> out=new ArrayList<>();
        try(Connection c=open();PreparedStatement p=c.prepareStatement(sql)){if(value!=null){p.setString(1,escapeQuery(value));p.setInt(2,limit);}try(var rs=p.executeQuery()){while(rs.next())out.add(new Entry(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)));}}
        catch(Exception e){return List.of();} return List.copyOf(out);
    }
    public void clear(){try(Connection c=open();var s=c.createStatement()){s.executeUpdate("DELETE FROM memory_fts");s.executeUpdate("DELETE FROM memory");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String escapeQuery(String value){return '"'+value.replace("\"","\"\"")+'"';}
    private static String sha256(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
