package com.cau.capstone8.backend.library;

import static com.cau.capstone8.backend.library.LibraryModels.*;
import com.cau.capstone8.backend.book.CoverUrl;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional
public class LibraryService {
    private final JdbcTemplate jdbc;
    public LibraryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SHELF_SELECT="""
        SELECT s.book_id,b.title,b.author,b.cover_url,b.cover_source_url,s.status,s.note,s.updated_at,
               r.difficulty,r.text AS review_text
        FROM backend.reading_shelf s JOIN backend.book b ON b.id=s.book_id
        LEFT JOIN backend.book_review r ON r.user_id=s.user_id AND r.book_id=s.book_id
        """;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<ShelfEntry> shelf(long user,int page,int size,ReadingStatus status) {
        requireUser(user);
        String filter=status==null?"":" AND s.status=?";
        Object[] countArgs=status==null?new Object[]{user}:new Object[]{user,status.name()};
        long total=jdbc.queryForObject("SELECT count(*) FROM backend.reading_shelf s WHERE s.user_id=?"+filter,Long.class,countArgs);
        long offset=(long)page*size;
        Object[] args=status==null?new Object[]{user,size,offset}:new Object[]{user,status.name(),size,offset};
        List<ShelfEntry> rows=offset>=total?List.of():jdbc.query(SHELF_SELECT+" WHERE s.user_id=?"+filter+" ORDER BY s.updated_at DESC,s.book_id DESC LIMIT ? OFFSET ?",this::mapShelf,args);
        return new Page<>(rows,page,size,total,(total+size-1)/size);
    }

    public ShelfEntry add(long user,long book) {
        requireReferences(user,book);
        jdbc.update("INSERT INTO backend.reading_shelf(user_id,book_id,status,note) VALUES(?,?,'WANT_TO_READ','') ON CONFLICT(user_id,book_id) DO UPDATE SET updated_at=clock_timestamp()",user,book);
        return entry(user,book);
    }

    public ShelfEntry save(long user,long book,ShelfRequest request) {
        requireReferences(user,book);
        jdbc.update("""
            INSERT INTO backend.reading_shelf(user_id,book_id,status,note) VALUES(?,?,?,?)
            ON CONFLICT(user_id,book_id) DO UPDATE SET status=excluded.status,note=excluded.note,updated_at=clock_timestamp()
            """,user,book,request.status().name(),request.note());
        return entry(user,book);
    }

    public void remove(long user,long book) {
        requireReferences(user,book);
        jdbc.update("DELETE FROM backend.reading_shelf WHERE user_id=? AND book_id=?",user,book);
    }

    public OwnReview saveReview(long user,long book,ReviewRequest request) {
        requireReferences(user,book);
        String text=request.text().strip();
        jdbc.update("""
            INSERT INTO backend.book_review(user_id,book_id,difficulty,text) VALUES(?,?,?,?)
            ON CONFLICT(user_id,book_id) DO UPDATE SET difficulty=excluded.difficulty,text=excluded.text,updated_at=clock_timestamp()
            """,user,book,request.difficulty().name(),text);
        return new OwnReview(request.difficulty(),text);
    }

    public void removeReview(long user,long book) {
        requireReferences(user,book);
        jdbc.update("DELETE FROM backend.book_review WHERE user_id=? AND book_id=?",user,book);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<PublicReview> reviews(long book,int page,int size) {
        requireBook(book);
        long total=jdbc.queryForObject("SELECT count(*) FROM backend.book_review WHERE book_id=?",Long.class,book);
        long offset=(long)page*size;
        List<PublicReview> rows=offset>=total?List.of():jdbc.query("""
            SELECT user_id,difficulty,text,updated_at FROM backend.book_review
            WHERE book_id=? ORDER BY updated_at DESC,user_id DESC LIMIT ? OFFSET ?
            """,(rs,n)->new PublicReview("독자 "+rs.getLong("user_id"),Difficulty.valueOf(rs.getString("difficulty")),rs.getString("text"),rs.getTimestamp("updated_at").toInstant().toString()),book,size,offset);
        return new Page<>(rows,page,size,total,(total+size-1)/size);
    }

    private ShelfEntry entry(long user,long book) {
        return jdbc.queryForObject(SHELF_SELECT+" WHERE s.user_id=? AND s.book_id=?",this::mapShelf,user,book);
    }
    private ShelfEntry mapShelf(ResultSet rs,int n) throws SQLException {
        String difficulty=rs.getString("difficulty");
        return new ShelfEntry(rs.getLong("book_id"),rs.getString("title"),rs.getString("author"),
                CoverUrl.reviewed(rs.getString("cover_url"),rs.getString("cover_source_url")),ReadingStatus.valueOf(rs.getString("status")),
                rs.getString("note"),rs.getTimestamp("updated_at").toInstant().toString(),difficulty==null?null:new OwnReview(Difficulty.valueOf(difficulty),rs.getString("review_text")));
    }
    private void requireReferences(long user,long book) { requireUser(user); requireBook(book); }
    private void requireUser(long user) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM backend.app_user WHERE id=?)",Boolean.class,user)))throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
    }
    private void requireBook(long book) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM backend.book WHERE id=?)",Boolean.class,book)))throw new ResourceNotFoundException("도서를 찾을 수 없습니다.");
    }
}
