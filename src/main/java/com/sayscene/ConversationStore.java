package com.sayscene;

import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

@Component
public class ConversationStore {
    private final StringRedisTemplate redis;
    private final long ttl;
    public ConversationStore(StringRedisTemplate redis, @Value("${conversation.ttl-seconds:86400}") long ttl) { this.redis = redis; this.ttl = ttl; }
    public String read(String id) { return redis.opsForValue().get("sayscene:conversation:" + id); }
    // 조회와 변경 사이에 다른 파드가 쓰더라도 오래된 상태로 덮어쓰지 않는다.
    public boolean replace(String id, String expected, String next) {
        var script = new DefaultRedisScript<Long>("""
            local old = redis.call('GET', KEYS[1])
            if (not old and ARGV[1] == '') or old == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1
            end
            return 0
            """, Long.class);
        return Long.valueOf(1).equals(redis.execute(script, List.of("sayscene:conversation:" + id), expected == null ? "" : expected, next, Long.toString(ttl)));
    }
}
