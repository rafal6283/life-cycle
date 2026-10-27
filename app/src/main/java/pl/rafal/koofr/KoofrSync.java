package pl.rafal.koofr;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kopia zapasowa i podgląd za zgodą na rodzinnym koncie Koofr — wersja na JVM/Androida,
 * zgodna z pakietem Dart koofr_sync (ten sam układ folderów i format plików).
 * Wszystkie metody blokują wątek — wywoływać poza wątkiem UI.
 */
public final class KoofrSync {

    /** Bezpieczny magazyn klucz–wartość (na Androidzie: EncryptedSharedPreferences). */
    public interface Store {
        String read(String key);

        void write(String key, String value);

        void delete(String key);
    }

    public static final class Config {
        public final String email, appPassword, root;

        public Config(String email, String appPassword, String root) {
            this.email = email.trim();
            this.appPassword = appPassword.trim();
            this.root = root == null || root.trim().isEmpty() ? "Rodzina" : root.trim();
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("e", email);
            m.put("p", appPassword);
            m.put("r", root);
            return m;
        }

        static Config fromJson(Map<String, Object> j) {
            return new Config(str(j.get("e")), str(j.get("p")), str(j.get("r")));
        }

        private static final String PREFIX = "KSYNC1:";

        /** Kod konfiguracji (zawiera hasło aplikacji) — wspólny ze wszystkimi rodzinnymi aplikacjami. */
        public String toCode() {
            return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Json.write(toJson()).getBytes(StandardCharsets.UTF_8));
        }

        public static Config fromCode(String text) {
            String t = text.trim().replaceAll("\\s", "");
            if (!t.startsWith(PREFIX)) return null;
            try {
                String b = t.substring(PREFIX.length()).replace('+', '-').replace('/', '_');
                Config c = fromJson(Json.obj(new String(java.util.Base64.getUrlDecoder().decode(b.replace("=", "")), StandardCharsets.UTF_8)));
                return c.email.isEmpty() || c.appPassword.isEmpty() ? null : c;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static final class Member {
        public final String id, name, codeCheck;
        public final Map<String, String> keys;
        public final Long updatedAt;

        Member(String id, String name, String codeCheck, Map<String, String> keys, Long updatedAt) {
            this.id = id;
            this.name = name;
            this.codeCheck = codeCheck;
            this.keys = keys;
            this.updatedAt = updatedAt;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", 1L);
            m.put("id", id);
            m.put("name", name);
            m.put("codeCheck", codeCheck);
            m.put("keys", new LinkedHashMap<String, Object>(keys));
            m.put("updatedAt", updatedAt);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Member fromJson(Map<String, Object> j) {
            Map<String, String> keys = new LinkedHashMap<>();
            Object k = j.get("keys");
            if (k instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) k).entrySet()) keys.put(e.getKey(), str(e.getValue()));
            String id = str(j.get("id"));
            String name = j.get("name") == null ? id : str(j.get("name"));
            Object u = j.get("updatedAt");
            return new Member(id, name, (String) j.get("codeCheck"), keys, u instanceof Number ? ((Number) u).longValue() : null);
        }
    }

    public static final class BackupInfo {
        public final String user, file, device;
        public final long at, size;

        BackupInfo(String user, String file, long at, String device, long size) {
            this.user = user;
            this.file = file;
            this.at = at;
            this.device = device;
            this.size = size;
        }

        private static final Pattern RE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})?_([\\w-]+)\\.ksy$");

        static BackupInfo parse(String user, WebDav.Entry e) {
            Matcher m = RE.matcher(e.name);
            if (e.isDir || !m.matches()) return null;
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            return new BackupInfo(user, e.name, c.getTimeInMillis(), m.group(7), e.size);
        }

        static String nameFor(long t, String device) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t);
            return String.format(java.util.Locale.ROOT, "%04d%02d%02d-%02d%02d%02d_%s.ksy", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), device);
        }
    }

    /** Załącznik kopii: ścieżka względna i odczyt treści. */
    public static final class Blob {
        public interface Reader {
            byte[] read() throws Exception;
        }

        public final String path;
        public final Reader reader;

        public Blob(String path, Reader reader) {
            this.path = path;
            this.reader = reader;
        }
    }

    public final class Restored {
        public final byte[] data;
        public final Map<String, String> blobs;
        private final String user;

        Restored(byte[] data, Map<String, String> blobs, String user) {
            this.data = data;
            this.blobs = blobs;
            this.user = user;
        }

        public byte[] blob(String id) throws KsException {
            byte[] f = dav().get(app() + "/pliki/" + user + "/" + id + ".ksy");
            if (f == null) throw new KsException("Na Koofr brakuje załącznika kopii.");
            return crypto.openBackup(f);
        }
    }

    public static final class ShareLink {
        public final String owner, viewer;
        public final boolean ownerAgreed, viewerAgreed;
        public final Map<String, Object> scope;
        public final Long since;

        ShareLink(String owner, String viewer, boolean ownerAgreed, boolean viewerAgreed, Map<String, Object> scope, Long since) {
            this.owner = owner;
            this.viewer = viewer;
            this.ownerAgreed = ownerAgreed;
            this.viewerAgreed = viewerAgreed;
            this.scope = scope;
            this.since = since;
        }

        public boolean active() {
            return ownerAgreed && viewerAgreed;
        }

        String key() {
            return owner + "__" + viewer;
        }
    }

    // ================================================================

    public final String appId;
    public final Store store;
    private final DavHttp http;
    private final String davBase;

    public Config config;
    public String userId, deviceId, recoveryCode;
    public KsCrypto crypto;
    private WebDav dav;
    private final List<Runnable> listeners = Collections.synchronizedList(new ArrayList<>());

    public KoofrSync(String appId, Store store, DavHttp http, String davBase) {
        this.appId = appId;
        this.store = store;
        this.http = http;
        this.davBase = davBase;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    public boolean connected() {
        return config != null;
    }

    public boolean ready() {
        return config != null && userId != null && crypto != null;
    }

    synchronized WebDav dav() throws KsException {
        if (config == null) throw new KsException("Najpierw połącz z kontem Koofr.");
        if (dav == null) dav = new WebDav(http, config.email, config.appPassword, davBase);
        return dav;
    }

    private String root() {
        return config.root;
    }

    private String app() {
        return root() + "/" + appId;
    }

    private String memberPath(String id) {
        return root() + "/_czlonkowie/" + id + ".json";
    }

    private static final String K = "ks.";

    public void load() {
        String c = store.read(K + "config");
        try {
            config = c == null ? null : Config.fromJson(Json.obj(c));
        } catch (KsException e) {
            config = null;
        }
        userId = store.read(K + "user");
        deviceId = store.read(K + "device");
        if (deviceId == null) {
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(Character.forDigit(r.nextInt(36), 36));
            deviceId = sb.toString();
            store.write(K + "device", deviceId);
        }
        recoveryCode = store.read(K + "code");
        crypto = null;
        if (recoveryCode != null) {
            RecoveryCode code = RecoveryCode.parse(recoveryCode);
            if (code != null) crypto = KsCrypto.fromCode(code, appId);
        }
        dav = null;
        changed();
    }

    // ------------------------------------------------------------ połączenie

    public void connect(Config c) throws KsException {
        WebDav test = new WebDav(http, c.email, c.appPassword, davBase);
        test.check();
        test.ensureDir(c.root + "/_czlonkowie");
        config = c;
        dav = null;
        store.write(K + "config", Json.write(c.toJson()));
        changed();
    }

    public void disconnect() {
        if (ready()) {
            try {
                for (String v : new ArrayList<>(myConsents().keySet())) revokeAsOwner(v);
            } catch (Exception ignored) {
            }
        }
        for (String k : new String[] {"config", "user", "code", "consents", "backup.enabled", "backup.last", "share.last"}) store.delete(K + k);
        config = null;
        userId = null;
        recoveryCode = null;
        crypto = null;
        dav = null;
        meCache = null;
        changed();
    }

    // ------------------------------------------------------------ członkowie

    public List<Member> members() throws KsException {
        List<Member> out = new ArrayList<>();
        for (WebDav.Entry e : dav().list(root() + "/_czlonkowie")) {
            if (e.isDir || !e.name.endsWith(".json")) continue;
            Member m = member(e.name.substring(0, e.name.length() - 5));
            if (m != null) out.add(m);
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public Member member(String id) throws KsException {
        byte[] b = dav().get(memberPath(id));
        if (b == null) return null;
        try {
            return Member.fromJson(Json.obj(new String(b, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private Member meCache;

    public Member me() throws KsException {
        if (userId == null) return null;
        if (meCache == null) meCache = member(userId);
        return meCache;
    }

    public void join(String existingId, String newName, RecoveryCode code) throws KsException {
        String check = KsCrypto.checkOf(code);
        KsCrypto keys = KsCrypto.fromCode(code, appId);
        Member m = null;
        String id;
        if (existingId != null) {
            id = existingId;
            m = member(id);
            if (m != null && m.codeCheck != null && !m.codeCheck.equals(check)) {
                throw new KsException("Ten kod odzyskiwania nie pasuje do osoby „" + m.name + "”. Wpisz kod zapisany przy pierwszym podłączeniu tej osoby.");
            }
        } else {
            String base = slugify(newName);
            Set<String> taken = new HashSet<>();
            for (Member x : members()) taken.add(x.id);
            id = base;
            for (int i = 2; taken.contains(id); i++) id = base + "-" + i;
        }
        Map<String, String> k = new LinkedHashMap<>();
        if (m != null) k.putAll(m.keys);
        k.put(appId, keys.publicKeyB64());
        String name = m != null ? m.name : (newName != null ? newName.trim() : id);
        Member updated = new Member(id, name, check, k, System.currentTimeMillis());
        dav().put(memberPath(id), Json.write(updated.toJson()).getBytes(StandardCharsets.UTF_8));
        String text = code.format();
        store.write(K + "user", id);
        store.write(K + "code", text);
        userId = id;
        recoveryCode = text;
        crypto = keys;
        meCache = updated;
        changed();
    }

    public static String slugify(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT);
        String from = "ąćęłńóśźż", to = "acelnoszz";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            int i = from.indexOf(c);
            sb.append(i >= 0 ? to.charAt(i) : c);
        }
        s = sb.toString().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) return "osoba";
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    // ------------------------------------------------------------ kopie

    public boolean backupEnabled() {
        return "1".equals(store.read(K + "backup.enabled"));
    }

    public void setBackupEnabled(boolean v) {
        store.write(K + "backup.enabled", v ? "1" : "0");
        changed();
    }

    public Long lastBackup() {
        String s = store.read(K + "backup.last");
        return s == null ? null : Long.parseLong(s);
    }

    public boolean backupDue(long everyMs) {
        if (!ready() || !backupEnabled()) return false;
        Long last = lastBackup();
        return last == null || System.currentTimeMillis() - last >= everyMs;
    }

    private String myBackups() {
        return app() + "/kopie/" + userId;
    }

    private String myBlobs() {
        return app() + "/pliki/" + userId;
    }

    private static final byte[] PAYLOAD_MAGIC = {0x4B, 0x53, 0x42, 0x31}; // KSB1

    public interface Progress {
        void onProgress(int done, int total);
    }

    public BackupInfo backupNow(byte[] data, List<Blob> blobs, Progress progress) throws KsException {
        requireReady();
        long now = System.currentTimeMillis();
        Map<String, Object> refs = new LinkedHashMap<>();
        if (blobs != null && !blobs.isEmpty()) {
            Set<String> existing = new HashSet<>();
            for (WebDav.Entry e : dav().list(myBlobs())) existing.add(e.name);
            int i = 0;
            for (Blob b : blobs) {
                byte[] bytes;
                try {
                    bytes = b.reader.read();
                } catch (Exception e) {
                    throw new KsException("Nie da się odczytać pliku " + b.path);
                }
                String id = crypto.blobId(bytes);
                if (!existing.contains(id + ".ksy")) {
                    dav().putAtomic(myBlobs() + "/" + id + ".ksy", crypto.sealBackup(bytes));
                    existing.add(id + ".ksy");
                }
                refs.put(b.path, id);
                if (progress != null) progress.onProgress(++i, blobs.size());
            }
        }
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("v", 2L);
        head.put("blobs", refs);
        byte[] header = Json.write(head).getBytes(StandardCharsets.UTF_8);
        byte[] payload = KsCrypto.concat(PAYLOAD_MAGIC, ByteBuffer.allocate(4).putInt(header.length).array(), header, data);
        String name = BackupInfo.nameFor(now, deviceId);
        if (!refs.isEmpty()) {
            List<Object> ids = new ArrayList<>(new HashSet<>(refs.values()));
            dav().putAtomic(myBackups() + "/" + refsName(name), crypto.sealBackup(Json.write(ids).getBytes(StandardCharsets.UTF_8)));
        }
        byte[] sealed = crypto.sealBackup(payload);
        dav().putAtomic(myBackups() + "/" + name, sealed);
        store.write(K + "backup.last", Long.toString(now));
        try {
            prune(7, 4);
        } catch (Exception ignored) {
        }
        changed();
        return new BackupInfo(userId, name, now / 1000 * 1000, deviceId, sealed.length);
    }

    public List<BackupInfo> backups() throws KsException {
        requireReady();
        List<BackupInfo> out = new ArrayList<>();
        for (WebDav.Entry e : dav().list(myBackups())) {
            BackupInfo b = BackupInfo.parse(userId, e);
            if (b != null) out.add(b);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    public Restored restore(BackupInfo b) throws KsException {
        requireReady();
        byte[] f = dav().get(app() + "/kopie/" + b.user + "/" + b.file);
        if (f == null) throw new KsException("Kopia zniknęła z Koofr.");
        byte[] p = crypto.openBackup(f);
        boolean hasHeader = p.length >= 8 && Arrays.equals(Arrays.copyOf(p, 4), PAYLOAD_MAGIC);
        if (!hasHeader) return new Restored(p, new LinkedHashMap<>(), b.user);
        int len = ByteBuffer.wrap(p, 4, 4).getInt();
        Map<String, Object> h = Json.obj(new String(p, 8, len, StandardCharsets.UTF_8));
        Map<String, String> refs = new LinkedHashMap<>();
        Object bl = h.get("blobs");
        if (bl instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) bl).entrySet()) refs.put((String) e.getKey(), str(e.getValue()));
        return new Restored(Arrays.copyOfRange(p, 8 + len, p.length), refs, b.user);
    }

    private static String refsName(String backupFile) {
        return backupFile.replaceAll("\\.ksy$", ".refs");
    }

    public void deleteBackup(BackupInfo b) throws KsException {
        dav().delete(app() + "/kopie/" + b.user + "/" + refsName(b.file));
        dav().delete(app() + "/kopie/" + b.user + "/" + b.file);
        changed();
    }

    public int prune(int daily, int weekly) throws KsException {
        List<BackupInfo> all = backups();
        List<BackupInfo> del = selectForPrune(all, daily, weekly);
        for (BackupInfo b : del) {
            dav().delete(app() + "/kopie/" + b.user + "/" + refsName(b.file));
            dav().delete(app() + "/kopie/" + b.user + "/" + b.file);
        }
        List<WebDav.Entry> blobFiles = dav().list(myBlobs());
        if (!blobFiles.isEmpty()) {
            Set<String> used = new HashSet<>();
            for (BackupInfo b : all) {
                if (del.contains(b)) continue;
                try {
                    byte[] r = dav().get(app() + "/kopie/" + b.user + "/" + refsName(b.file));
                    if (r != null) {
                        for (Object o : (List<?>) Json.parse(new String(crypto.openBackup(r), StandardCharsets.UTF_8))) used.add(str(o));
                    }
                } catch (Exception e) {
                    return del.size(); // nie wiemy na pewno, co jest używane — nic nie kasujemy
                }
            }
            for (WebDav.Entry f : blobFiles) {
                String id = f.name.replace(".ksy", "");
                if (f.name.endsWith(".ksy") && !used.contains(id)) dav().delete(myBlobs() + "/" + f.name);
            }
        }
        return del.size();
    }

    static List<BackupInfo> selectForPrune(List<BackupInfo> all, int daily, int weekly) {
        List<BackupInfo> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> Long.compare(b.at, a.at));
        Set<String> days = new HashSet<>(), weeks = new HashSet<>();
        Set<BackupInfo> keep = new HashSet<>();
        for (BackupInfo b : sorted) {
            String d = day(b.at);
            if (days.size() < daily && !days.contains(d)) {
                days.add(d);
                weeks.add(week(b.at));
                keep.add(b);
            }
        }
        Set<String> weeklyKept = new HashSet<>();
        for (BackupInfo b : sorted) {
            if (keep.contains(b)) continue;
            String w = week(b.at);
            if (weeks.contains(w) || weeklyKept.size() >= weekly) continue;
            weeks.add(w);
            weeklyKept.add(w);
            keep.add(b);
        }
        List<BackupInfo> out = new ArrayList<>();
        for (BackupInfo b : sorted) if (!keep.contains(b)) out.add(b);
        return out;
    }

    private static String day(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    private static String week(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // poniedziałek = 0
        c.add(Calendar.DAY_OF_MONTH, -dow);
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    // ------------------------------------------------------------ podgląd za zgodą

    private static final String OWNER_FILE = "wlasciciel.json", VIEWER_FILE = "ogladajacy.json";

    private String linkDir(String owner, String viewer) {
        return app() + "/zgody/" + owner + "__" + viewer;
    }

    private String dataPath(String owner, String viewer) {
        return app() + "/dane/" + owner + "__" + viewer + ".ksy";
    }

    /** Zgody udzielone z TEGO telefonu: oglądający → {scope, at, seen}. One decydują o wysyłce. */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> myConsents() {
        String s = store.read(K + "consents");
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (s == null) return out;
        try {
            for (Map.Entry<String, Object> e : Json.obj(s).entrySet()) out.put(e.getKey(), (Map<String, Object>) e.getValue());
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveConsents(Map<String, Map<String, Object>> c) {
        store.write(K + "consents", Json.write(c));
    }

    @SuppressWarnings("unchecked")
    public List<ShareLink> links() throws KsException {
        requireReady();
        List<ShareLink> out = new ArrayList<>();
        for (WebDav.Entry d : dav().list(app() + "/zgody")) {
            if (!d.isDir) continue;
            String[] parts = d.name.split("__");
            if (parts.length != 2 || (!parts[0].equals(userId) && !parts[1].equals(userId))) continue;
            Set<String> files = new HashSet<>();
            for (WebDav.Entry f : dav().list(app() + "/zgody/" + d.name)) files.add(f.name);
            Map<String, Object> scope = new LinkedHashMap<>();
            Long since = null;
            if (files.contains(OWNER_FILE)) {
                byte[] b = dav().get(app() + "/zgody/" + d.name + "/" + OWNER_FILE);
                if (b != null) {
                    try {
                        Map<String, Object> j = Json.obj(new String(b, StandardCharsets.UTF_8));
                        if (j.get("scope") instanceof Map) scope = (Map<String, Object>) j.get("scope");
                        if (j.get("at") instanceof Number) since = ((Number) j.get("at")).longValue();
                    } catch (Exception ignored) {
                    }
                }
            }
            out.add(new ShareLink(parts[0], parts[1], files.contains(OWNER_FILE), files.contains(VIEWER_FILE), scope, since));
        }
        return out;
    }

    public void requestView(String ownerId) throws KsException {
        requireReady();
        Member owner = member(ownerId);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("v", 1L);
        j.put("owner", ownerId);
        j.put("viewer", userId);
        j.put("at", System.currentTimeMillis());
        j.put("ownerKey", owner == null ? null : owner.keys.get(appId));
        dav().put(linkDir(ownerId, userId) + "/" + VIEWER_FILE, Json.write(j).getBytes(StandardCharsets.UTF_8));
        changed();
    }

    public void allowView(String viewerId, Map<String, Object> scope) throws KsException {
        requireReady();
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("v", 1L);
        j.put("owner", userId);
        j.put("viewer", viewerId);
        j.put("at", System.currentTimeMillis());
        j.put("scope", scope);
        dav().put(linkDir(userId, viewerId) + "/" + OWNER_FILE, Json.write(j).getBytes(StandardCharsets.UTF_8));
        Map<String, Map<String, Object>> c = myConsents();
        Map<String, Object> prev = c.get(viewerId);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("scope", scope);
        e.put("at", System.currentTimeMillis());
        e.put("seen", prev != null && Boolean.TRUE.equals(prev.get("seen")));
        c.put(viewerId, e);
        saveConsents(c);
        changed();
    }

    public void revokeAsOwner(String viewerId) throws KsException {
        requireReady();
        Map<String, Map<String, Object>> c = myConsents();
        c.remove(viewerId);
        saveConsents(c);
        dav().delete(dataPath(userId, viewerId));
        dav().delete(linkDir(userId, viewerId) + "/" + OWNER_FILE);
        cleanupLinkDir(userId, viewerId);
        changed();
    }

    public void revokeAsViewer(String ownerId) throws KsException {
        requireReady();
        dav().delete(dataPath(ownerId, userId));
        dav().delete(linkDir(ownerId, userId) + "/" + VIEWER_FILE);
        cleanupLinkDir(ownerId, userId);
        changed();
    }

    /** Odrzucenie prośby lub propozycji: znika całe powiązanie. */
    public void dismissLink(String owner, String viewer) throws KsException {
        requireReady();
        if (!owner.equals(userId) && !viewer.equals(userId)) throw new KsException("To powiązanie nie dotyczy Ciebie.");
        if (owner.equals(userId)) {
            Map<String, Map<String, Object>> c = myConsents();
            if (c.remove(viewer) != null) saveConsents(c);
        }
        dav().delete(dataPath(owner, viewer));
        dav().delete(linkDir(owner, viewer));
        changed();
    }

    private void cleanupLinkDir(String owner, String viewer) throws KsException {
        if (dav().list(linkDir(owner, viewer)).isEmpty()) dav().delete(linkDir(owner, viewer));
    }

    public interface ShareBuilder {
        byte[] build(String viewerId, Map<String, Object> scope) throws Exception;
    }

    /** Wysyła dane wszystkim z aktywnym podglądem i sprząta po cofniętych zgodach. Zwraca liczbę odbiorców. */
    public synchronized int publishShares(ShareBuilder builder) throws KsException {
        if (!ready()) return 0;
        Map<String, Map<String, Object>> consents = myConsents();
        Map<String, ShareLink> byKey = new HashMap<>();
        for (ShareLink l : links()) byKey.put(l.key(), l);
        int sent = 0;
        boolean dirty = false;
        for (String viewer : new ArrayList<>(consents.keySet())) {
            ShareLink l = byKey.get(userId + "__" + viewer);
            Map<String, Object> c = consents.get(viewer);
            if (l == null || !l.ownerAgreed) {
                consents.remove(viewer);
                dirty = true;
                dav().delete(dataPath(userId, viewer));
                continue;
            }
            if (!l.viewerAgreed) {
                if (Boolean.TRUE.equals(c.get("seen"))) {
                    consents.remove(viewer);
                    dirty = true;
                    dav().delete(dataPath(userId, viewer));
                    dav().delete(linkDir(userId, viewer) + "/" + OWNER_FILE);
                    cleanupLinkDir(userId, viewer);
                }
                continue;
            }
            Member m = member(viewer);
            String key = m == null ? null : m.keys.get(appId);
            if (key == null) continue;
            if (!Boolean.TRUE.equals(c.get("seen"))) {
                c.put("seen", true);
                dirty = true;
            }
            byte[] data;
            try {
                data = builder.build(viewer, l.scope);
            } catch (Exception e) {
                throw new KsException("Nie udało się przygotować danych do podglądu: " + e.getMessage());
            }
            dav().putAtomic(dataPath(userId, viewer), crypto.sealFor(KsCrypto.parsePublicKey(key), data));
            sent++;
        }
        for (WebDav.Entry f : dav().list(app() + "/dane")) {
            String[] parts = f.name.replace(".ksy", "").replace(".tmp", "").split("__");
            if (parts.length == 2 && parts[0].equals(userId) && !consents.containsKey(parts[1])) dav().delete(app() + "/dane/" + f.name);
        }
        if (dirty) saveConsents(consents);
        store.write(K + "share.last", Long.toString(System.currentTimeMillis()));
        changed();
        return sent;
    }

    /** Dane udostępnione mi przez ownerId albo null; [0] = dane, [1] = czas modyfikacji (Long lub null). */
    public Object[] fetchShared(String ownerId) throws KsException {
        requireReady();
        byte[] f = dav().get(dataPath(ownerId, userId));
        if (f == null) return null;
        Member m = member(ownerId);
        String key = m == null ? null : m.keys.get(appId);
        if (key == null) throw new KsException("Ta osoba nie ma klucza w tej aplikacji.");
        Long mod = null;
        for (WebDav.Entry e : dav().list(app() + "/dane")) if (e.name.equals(ownerId + "__" + userId + ".ksy")) mod = e.modified;
        return new Object[] {crypto.openFrom(KsCrypto.parsePublicKey(key), f), mod};
    }

    public String safetyNumberWith(String otherId) throws KsException {
        if (!ready()) return null;
        Member m = member(otherId);
        String key = m == null ? null : m.keys.get(appId);
        if (key == null) return null;
        return KsCrypto.safetyNumber(appId, crypto.publicKey, KsCrypto.parsePublicKey(key));
    }

    private void requireReady() throws KsException {
        if (!ready()) throw new KsException("Najpierw połącz z Koofr i wybierz osobę.");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    @SuppressWarnings("unused")
    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
