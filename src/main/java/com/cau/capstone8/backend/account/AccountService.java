package com.cau.capstone8.backend.account;

import static com.cau.capstone8.backend.account.AccountModels.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Session register(Register input) {
        long user = jdbc.queryForObject(
                "INSERT INTO backend.app_user(display_name) VALUES (?) RETURNING id",
                Long.class, input.displayName().strip());
        try {
            jdbc.update("INSERT INTO backend.user_account(user_id,email,password_hash) VALUES (?,?,?)",
                    user, normalize(input.email()), passwords.encode(input.password()));
        } catch (DuplicateKeyException duplicate) {
            throw new AccountException(409, "ACCOUNT_EXISTS", "이미 사용 중인 이메일입니다.");
        }
        return issue(user);
    }

    @Transactional
    public Session login(Login input) {
        var rows = jdbc.query("SELECT user_id,password_hash FROM backend.user_account WHERE email=?",
                (rs,n) -> new Credential(rs.getLong(1), rs.getString(2)), normalize(input.email()));
        String hash = rows.isEmpty() ? dummyHash : rows.getFirst().hash();
        boolean matches = passwords.matches(input.password(), hash);
        if (rows.isEmpty() || !matches) {
            throw new AccountException(401, "INVALID_CREDENTIALS", "이메일과 비밀번호를 확인해 주세요.");
        }
        return issue(rows.getFirst().user());
    }

    private Session issue(long user) {
        jdbc.update("DELETE FROM backend.account_session WHERE user_id=? AND expires_at<=now()", user);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(12);
        jdbc.update("INSERT INTO backend.account_session(token_hash,user_id,expires_at) VALUES (?,?,?)",
                hashToken(token), user, expiry);
        return new Session(user, token, "Bearer", expiry);
    }

    public Optional<Long> authenticate(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return jdbc.query("SELECT user_id FROM backend.account_session WHERE token_hash=? AND expires_at>now()",
                (rs,n) -> rs.getLong(1), hashToken(token)).stream().findFirst();
    }

    public void logout(String token) {
        jdbc.update("DELETE FROM backend.account_session WHERE token_hash=?", hashToken(token));
    }

    @Transactional(readOnly=true)
    public Profile profile(long user) {
        var interests = jdbc.query("""
                SELECT t.id,t.code,t.name FROM backend.user_interest i
                JOIN backend.topic t ON t.id=i.topic_id WHERE i.user_id=? ORDER BY t.id
                """, (rs,n) -> new Interest(rs.getLong(1),rs.getString(2),rs.getString(3)), user);
        return jdbc.queryForObject("""
                SELECT u.id,a.email,u.display_name,u.bio,u.avatar_key,u.created_at
                FROM backend.app_user u JOIN backend.user_account a ON a.user_id=u.id WHERE u.id=?
                """, (rs,n) -> new Profile(rs.getLong(1),rs.getString(2),rs.getString(3),
                        rs.getString(4),Avatar.valueOf(rs.getString(5)),interests,
                        rs.getObject(6,OffsetDateTime.class)), user);
    }

    @Transactional
    public Profile update(long user, Update input) {
        jdbc.queryForObject("SELECT id FROM backend.app_user WHERE id=? FOR UPDATE",Long.class,user);
        if (new HashSet<>(input.interestTopicIds()).size() != input.interestTopicIds().size()) {
            throw new AccountException(400,"INVALID_INTERESTS","관심 분야는 중복 없이 선택해 주세요.");
        }
        for (long topic : input.interestTopicIds()) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM backend.topic WHERE id=?)",Boolean.class,topic))) {
                throw new AccountException(400,"INVALID_INTERESTS","등록된 관심 분야를 선택해 주세요.");
            }
        }
        jdbc.update("UPDATE backend.app_user SET display_name=?,bio=?,avatar_key=?,updated_at=now() WHERE id=?",
                input.displayName().strip(),input.bio().strip(),input.avatarKey().name(),user);
        jdbc.update("DELETE FROM backend.user_interest WHERE user_id=?",user);
        for (long topic : input.interestTopicIds()) {
            jdbc.update("INSERT INTO backend.user_interest(user_id,topic_id) VALUES (?,?)",user,topic);
        }
        return profile(user);
    }

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private record Credential(long user, String hash) {}
}
