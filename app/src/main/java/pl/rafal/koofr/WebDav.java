package pl.rafal.koofr;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimalny klient WebDAV (Koofr: https://app.koofr.net/dav/Koofr/), jak w pakiecie koofr_sync. */
public final class WebDav {
    public static final String KOOFR = "https://app.koofr.net/dav/Koofr/";

    public static final class Entry {
        public final String name;
        public final boolean isDir;
        public final long size;
        public final Long modified;

        Entry(String name, boolean isDir, long size, Long modified) {
            this.name = name;
            this.isDir = isDir;
            this.size = size;
            this.modified = modified;
        }
    }

    private final String base;
    private final String auth;
    private final DavHttp http;
    private final Set<String> dirs = new HashSet<>();

    public WebDav(DavHttp http, String user, String password, String base) {
        this.http = http;
        this.base = base == null ? KOOFR : (base.endsWith("/") ? base : base + "/");
        this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    String url(String path) {
        StringBuilder sb = new StringBuilder(base);
        boolean first = true;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (!first) sb.append('/');
            first = false;
            try {
                sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        if (path.endsWith("/") && !first) sb.append('/');
        return sb.toString();
    }

    private DavHttp.Response send(String method, String path, Map<String, String> headers, byte[] body) throws KsException {
        Map<String, String> h = headers == null ? new HashMap<>() : new HashMap<>(headers);
        h.put("Authorization", auth);
        try {
            return http.send(method, url(path), h, body);
        } catch (SocketTimeoutException e) {
            throw new KsException("Koofr nie odpowiada (przekroczony czas).");
        } catch (UnknownHostException e) {
            throw new KsException("Brak połączenia z internetem.");
        } catch (IOException e) {
            throw new KsException("Błąd połączenia z Koofr: " + e.getMessage());
        }
    }

    public void check() throws KsException {
        Map<String, String> h = new HashMap<>();
        h.put("Depth", "0");
        DavHttp.Response r = send("PROPFIND", "", h, null);
        if (r.status != 207 && r.status != 200) throw KsException.http(r.status, "katalog główny");
    }

    /** Zawartość katalogu (bez niego samego); pusta lista, gdy katalog nie istnieje. */
    public List<Entry> list(String dir) throws KsException {
        String path = dir.endsWith("/") ? dir : dir + "/";
        Map<String, String> h = new HashMap<>();
        h.put("Depth", "1");
        DavHttp.Response r = send("PROPFIND", path, h, null);
        if (r.status == 404) return new ArrayList<>();
        if (r.status != 207) throw KsException.http(r.status, dir);
        String self = decode(pathOf(url(path)));
        String selfTrim = self.endsWith("/") ? self.substring(0, self.length() - 1) : self;
        List<Entry> out = new ArrayList<>();
        for (Object[] e : parseMultistatus(new String(r.body, StandardCharsets.UTF_8))) {
            if (!e[0].equals(selfTrim)) out.add((Entry) e[1]);
        }
        return out;
    }

    private static String pathOf(String url) {
        int i = url.indexOf("://");
        if (i < 0) return url;
        int j = url.indexOf('/', i + 3);
        return j < 0 ? "/" : url.substring(j);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static final Pattern RESP = Pattern.compile("<(?:[\\w-]+:)?response\\b[^>]*>(.*?)</(?:[\\w-]+:)?response>", Pattern.DOTALL);
    private static final Pattern COLL = Pattern.compile("<(?:[\\w-]+:)?collection\\b");

    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<(?:[\\w-]+:)?" + name + "\\b[^>]*>(.*?)</(?:[\\w-]+:)?" + name + ">", Pattern.DOTALL).matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Odpowiedź PROPFIND → [ścieżka href bez końcowego „/”, Entry]. */
    static List<Object[]> parseMultistatus(String xml) {
        List<Object[]> out = new ArrayList<>();
        Matcher m = RESP.matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            String href = tag(block, "href");
            if (href == null) continue;
            href = decode(href.startsWith("http") ? pathOf(href) : href);
            String trimmed = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
            String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            boolean isDir = COLL.matcher(block).find();
            long size = 0;
            try {
                String cl = tag(block, "getcontentlength");
                if (cl != null) size = Long.parseLong(cl);
            } catch (NumberFormatException ignored) {
            }
            Long mod = null;
            String lm = tag(block, "getlastmodified");
            if (lm != null) {
                try {
                    SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                    mod = f.parse(lm).getTime();
                } catch (Exception ignored) {
                }
            }
            out.add(new Object[] {trimmed, new Entry(name, isDir, size, mod)});
        }
        return out;
    }

    /** Plik albo null, gdy go nie ma. */
    public byte[] get(String path) throws KsException {
        DavHttp.Response r = send("GET", path, null, null);
        if (r.status == 404) return null;
        if (r.status != 200) throw KsException.http(r.status, path);
        return r.body;
    }

    public void put(String path, byte[] data) throws KsException {
        int i = path.lastIndexOf('/');
        if (i > 0) ensureDir(path.substring(0, i));
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/octet-stream");
        DavHttp.Response r = send("PUT", path, h, data);
        if (r.status != 200 && r.status != 201 && r.status != 204) throw KsException.http(r.status, path);
    }

    /** Najpierw plik tymczasowy, potem przeniesienie — czytający nie dostanie połowy pliku. */
    public void putAtomic(String path, byte[] data) throws KsException {
        String tmp = path + ".tmp";
        put(tmp, data);
        try {
            move(tmp, path);
        } catch (KsException e) {
            delete(tmp);
            throw e;
        }
    }

    public void ensureDir(String dir) throws KsException {
        String cur = "";
        for (String seg : dir.split("/")) {
            if (seg.isEmpty()) continue;
            cur = cur.isEmpty() ? seg : cur + "/" + seg;
            if (dirs.contains(cur)) continue;
            DavHttp.Response r = send("MKCOL", cur + "/", null, null);
            if (r.status != 201 && r.status != 405 && r.status != 200) throw KsException.http(r.status, cur);
            dirs.add(cur);
        }
    }

    public void delete(String path) throws KsException {
        DavHttp.Response r = send("DELETE", path, null, null);
        if (r.status != 200 && r.status != 204 && r.status != 404) throw KsException.http(r.status, path);
        dirs.removeIf(d -> d.equals(path) || d.startsWith(path + "/"));
    }

    public void move(String from, String to) throws KsException {
        Map<String, String> h = new HashMap<>();
        h.put("Destination", url(to));
        h.put("Overwrite", "T");
        DavHttp.Response r = send("MOVE", from, h, null);
        if (r.status != 201 && r.status != 204 && r.status != 200) throw KsException.http(r.status, from + " → " + to);
    }
}
