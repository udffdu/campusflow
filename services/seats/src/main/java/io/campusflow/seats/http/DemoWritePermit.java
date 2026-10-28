package io.campusflow.seats.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** No production bean/factory. Only the test-classpath fixture can install this local-demo permit. */
public final class DemoWritePermit {
    private final String origin;
    private final byte[] secret;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random=new SecureRandom();
    DemoWritePermit(String bind,String origin,byte[] secret,byte[] serviceKey,Clock clock,Duration ttl) {
        URI uri=URI.create(origin);
        if(!Set.of("127.0.0.1","::1").contains(bind) || !Set.of("127.0.0.1","localhost","[::1]","::1").contains(uri.getHost())
                || !Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null
                || !uri.getPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null
                || secret.length<32 || MessageDigest.isEqual(secret,serviceKey) || ttl.isNegative()||ttl.isZero()||ttl.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("隔离演示保护配置无效");
        this.origin=origin;this.secret=secret.clone();this.clock=clock;this.ttl=ttl;
    }
    public String issue(String session) {
        checkSession(session);byte[] nonce=new byte[16];random.nextBytes(nonce);
        String payload=clock.instant().plus(ttl).getEpochSecond()+"."+encode(nonce);
        return payload+"."+encode(mac(session,payload));
    }
    public void verify(HttpServletRequest request,String session) {
        if(!origin.equals(single(request,"Origin")))throw new QueryProblem(403,"ORIGIN_REJECTED","请从本次演示页面提交");
        String site=single(request,"Sec-Fetch-Site");
        if(site!=null&&!Set.of("same-origin","none").contains(site))throw new QueryProblem(403,"ORIGIN_REJECTED","禁止跨站提交");
        verifyToken(single(request,"X-M1-CSRF-Token"),session);
    }
    void verifyToken(String token,String session) {
        checkSession(session);
        try {
            if(token==null||token.length()>160)throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=3||!parts[0].matches("[0-9]{1,12}")||!parts[1].matches("[A-Za-z0-9_-]{22}")||!parts[2].matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();
            long expiry=Long.parseLong(parts[0]),now=clock.instant().getEpochSecond();
            if(expiry<=now||expiry>now+ttl.getSeconds()||!MessageDigest.isEqual(mac(session,parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2])))throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) {throw new QueryProblem(403,"CSRF_INVALID","提交验证已失效，请重新验证后用原请求重试");}
    }
    public static String single(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()>1)throw QueryProblem.invalid();
        return values.isEmpty()?null:values.getFirst();
    }
    private static void checkSession(String session) {if(session==null||!session.matches("[A-Za-z0-9_-]{43}"))throw new QueryProblem(401,"SESSION_REQUIRED","请重新登录");}
    private static String encode(byte[] bytes) {return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private byte[] mac(String session,String payload) {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(("m1-csrf\n"+session+"\n"+payload).getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException e) {throw new IllegalStateException("CSRF验证不可用");}
    }
}
