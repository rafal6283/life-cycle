package pl.rafal.koofr;

import java.io.IOException;
import java.util.Map;

/** Transport HTTP z dowolną metodą (PROPFIND, MKCOL, MOVE). Na Androidzie: OkHttp; w testach: java.net.http. */
public interface DavHttp {
    final class Response {
        public final int status;
        public final byte[] body;

        public Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    Response send(String method, String url, Map<String, String> headers, byte[] body) throws IOException;
}
