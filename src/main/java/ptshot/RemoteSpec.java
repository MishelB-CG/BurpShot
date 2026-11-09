package ptshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative description of every "Save to remote" destination type. The UI is generated from
 * these specs; {@link RemoteClient} interprets the resulting key/value map.
 *
 * Conventions: CHECKBOX values are the strings "true"/"false"; CHOICE values equal the shown text;
 * Field.secret fields must never be persisted by the UI.
 */
final class RemoteSpec {
    private RemoteSpec() {}

    enum Kind { TEXT, PASSWORD, CHOICE, MULTILINE, CHECKBOX, FILE }

    static final class Field {
        final String key, label, def, help;
        final Kind kind;
        final String[] choices;
        final boolean secret;
        final String showIf;

        Field(String key, String label, String def, String help, Kind kind, String[] choices,
              boolean secret, String showIf) {
            this.key = key;
            this.label = label;
            this.def = def == null ? "" : def;
            this.help = help == null ? "" : help;
            this.kind = kind;
            this.choices = choices == null ? new String[0] : choices;
            this.secret = secret;
            this.showIf = showIf == null ? "" : showIf;
        }
    }

    static final class Type {
        final String id, title, description;
        final boolean plaintext;
        final List<Field> fields;

        Type(String id, String title, String description, boolean plaintext, List<Field> fields) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.plaintext = plaintext;
            this.fields = Collections.unmodifiableList(fields);
        }

        Field field(String key) {
            for (Field f : fields) if (f.key.equals(key)) return f;
            return null;
        }
    }

    private static final List<Type> TYPES = build();

    static List<Type> types() { return TYPES; }

    static Type type(String id) {
        if (id == null) return null;
        for (Type t : TYPES) if (t.id.equals(id)) return t;
        return null;
    }

    // ---- field helpers -------------------------------------------------------------------------

    private static Field txt(String key, String label, String def, String help, String showIf) {
        return new Field(key, label, def, help, Kind.TEXT, null, false, showIf);
    }
    private static Field pwd(String key, String label, String help, String showIf) {
        return new Field(key, label, "", help, Kind.PASSWORD, null, true, showIf);
    }
    private static Field secretTxt(String key, String label, String help, String showIf) {
        return new Field(key, label, "", help, Kind.PASSWORD, null, true, showIf);
    }
    private static Field choice(String key, String label, String def, String help, String showIf, String... ch) {
        return new Field(key, label, def, help, Kind.CHOICE, ch, false, showIf);
    }
    private static Field multi(String key, String label, String help, boolean secret, String showIf) {
        return new Field(key, label, "", help, Kind.MULTILINE, null, secret, showIf);
    }
    private static Field check(String key, String label, boolean def, String help, String showIf) {
        return new Field(key, label, def ? "true" : "false", help, Kind.CHECKBOX, null, false, showIf);
    }
    private static Field file(String key, String label, String help, String showIf) {
        return new Field(key, label, "", help, Kind.FILE, null, false, showIf);
    }
    private static Field viaBurp() {
        return check("viaBurp", "Send through Burp's network settings (recommended)", true,
                "Uses Burp's upstream proxy and TLS settings. Burp lists requests sent by extensions in its Logger, so the request - including its credentials - is visible there and may be saved in the project.", "");
    }
    private static Field insecure(String showIf) {
        return check("insecureTls", "Skip TLS certificate verification (INSECURE)", false,
                "Accepts any certificate and any host name. Only for lab systems; exposes credentials to interception.",
                showIf);
    }
    private static Field apiBase(String def, String help) {
        return txt("apiBase", "API base URL (advanced)", def, help, "");
    }

    private static List<Type> build() {
        List<Type> t = new ArrayList<>();

        // 1. Generic HTTP
        t.add(new Type("http", "HTTP(S) REST / WebDAV endpoint",
                "Uploads the file with PUT or POST. {name} (URL, parameter values and header values) is replaced by the "
                        + "file name; 'File name in URL' decides whether it is also appended to the URL path. Extra "
                        + "parameters go into the query string or into multipart form fields. The exact request can be "
                        + "viewed and edited before sending. Success is any 2xx response. Redirects are never followed. "
                        + "http:// URLs send everything unencrypted.",
                true, list(
                txt("url", "URL", "", "e.g. https://host/dav/folder/ or https://host/upload/{name}", ""),
                choice("nameMode", "File name in URL", "append to URL path",
                        "Use 'never in URL' for fixed endpoints such as https://host/api/upload", "",
                        "append to URL path", "only where {name} is", "never in URL"),
                choice("method", "Method", "PUT", "", "", "PUT", "POST"),
                choice("body", "Body", "raw", "raw = file bytes as the body", "", "raw", "multipart/form-data"),
                multi("params", "Extra parameters (name=value per line)",
                        "One per line; # starts a comment; {name} = file name; max 30. Treated as secret (may hold credentials).", true, ""),
                choice("paramsIn", "Send parameters as", "query string",
                        "Form fields need Body = multipart/form-data", "", "query string", "multipart form fields"),
                txt("multipartField", "Multipart field name", "file", "", "body=multipart/form-data"),
                choice("auth", "Authentication", "none", "", "",
                        "none", "basic", "bearer", "apikey-header", "apikey-query", "cookie"),
                txt("user", "Username", "", "", "auth=basic"),
                pwd("password", "Password", "", "auth=basic"),
                pwd("token", "Bearer token", "", "auth=bearer"),
                txt("keyName", "Header / parameter name", "X-API-Key", "", "auth=apikey-header|apikey-query"),
                secretTxt("keyValue", "API key value", "", "auth=apikey-header|apikey-query"),
                secretTxt("cookie", "Cookie", "e.g. session=abc123; other=1", "auth=cookie"),
                multi("headers", "Extra headers", "One 'Name: value' per line, max 20. Treated as secret (may hold credentials).", true, ""),
                viaBurp(),
                insecure(""))));

        // 2. S3
        t.add(new Type("s3", "Amazon S3 / S3-compatible (MinIO, Wasabi, R2...)",
                "PUT Object with AWS Signature V4 (header based). AWS endpoints use virtual-hosted style, custom "
                        + "endpoints use path style. An endpoint written as http://... is unencrypted. "
                        + "'pre-signed URL' mode PUTs to a URL you generated elsewhere (it embeds credentials).",
                false, list(
                choice("auth", "Authentication", "access key", "", "", "none", "access key", "pre-signed URL"),
                txt("endpoint", "Endpoint", "s3.amazonaws.com", "Blank = AWS regional endpoint. May start with http:// or https://", "auth=none|access key"),
                txt("region", "Region", "us-east-1", "", "auth=none|access key"),
                txt("bucket", "Bucket", "", "", "auth=none|access key"),
                txt("prefix", "Key prefix", "", "Optional 'folder/' inside the bucket", "auth=none|access key"),
                check("pathStyle", "Force path-style addressing", false, "", "auth=none|access key"),
                txt("accessKey", "Access key ID", "", "", "auth=access key"),
                pwd("secretKey", "Secret access key", "", "auth=access key"),
                pwd("sessionToken", "Session token", "Optional (temporary credentials)", "auth=access key"),
                secretTxt("presignedUrl", "Pre-signed PUT URL", "", "auth=pre-signed URL"),
                viaBurp(),
                insecure(""))));

        // 3. GCS
        t.add(new Type("gcs", "Google Cloud Storage",
                "Bearer mode uses the JSON upload API with an OAuth2 access token. HMAC mode uses the S3 "
                        + "interoperability endpoint. 'signed-url' PUTs to a V4 signed URL (it embeds credentials).",
                false, list(
                choice("auth", "Authentication", "bearer", "", "", "none", "bearer", "hmac", "signed-url"),
                txt("bucket", "Bucket", "", "", "auth=none|bearer|hmac"),
                txt("prefix", "Object prefix", "", "", "auth=none|bearer|hmac"),
                pwd("token", "OAuth2 access token", "gcloud auth print-access-token", "auth=bearer"),
                txt("accessId", "HMAC access ID", "", "", "auth=hmac"),
                pwd("secretKey", "HMAC secret", "", "auth=hmac"),
                secretTxt("signedUrl", "Signed URL (PUT)", "", "auth=signed-url"),
                apiBase("https://storage.googleapis.com", "Only change for testing"),
                viaBurp(),
                insecure(""))));

        // 4. Dropbox
        t.add(new Type("dropbox", "Dropbox",
                "Uploads with the Dropbox API (files up to 150 MB).", false, list(
                pwd("token", "Access token", "", ""),
                txt("folder", "Folder path", "/BurpShot", "", ""),
                choice("mode", "If the file exists", "add", "add = keep both (autorename)", "", "add", "overwrite"),
                apiBase("https://content.dropboxapi.com", "Only change for testing"),
                viaBurp(),
                insecure(""))));

        // 5. Google Drive
        t.add(new Type("gdrive", "Google Drive",
                "Multipart upload with the Drive v3 API using an OAuth2 access token.", false, list(
                pwd("token", "Access token", "", ""),
                txt("parent", "Parent folder ID", "", "Optional; blank = My Drive root", ""),
                apiBase("https://www.googleapis.com", "Only change for testing"),
                viaBurp(),
                insecure(""))));

        // 6. OneDrive
        t.add(new Type("onedrive", "Microsoft OneDrive / SharePoint (Graph)",
                "Simple upload through Microsoft Graph (files up to 4 MB only).", false, list(
                pwd("token", "Access token", "", ""),
                txt("folder", "Folder path", "BurpShot", "Relative to the drive root", ""),
                apiBase("https://graph.microsoft.com", "Only change for testing"),
                viaBurp(),
                insecure(""))));

        // 7. Azure
        t.add(new Type("azure", "Azure Blob Storage (SAS)",
                "PUT Blob authorised with a shared access signature token.", false, list(
                txt("container", "Container URL", "", "https://account.blob.core.windows.net/container", ""),
                secretTxt("sas", "SAS token", "With or without the leading '?'", ""),
                txt("prefix", "Blob prefix", "", "", ""),
                viaBurp(),
                insecure(""))));

        // 8. FTP
        t.add(new Type("ftp", "FTP / FTPS",
                "Plain FTP sends the password and data unencrypted. FTPS uses the JVM trust store and verifies "
                        + "the host name. The remote directory is created if missing.", true, list(
                txt("host", "Host", "", "", ""),
                txt("port", "Port", "21", "Implicit FTPS uses 990 when this is left at 21", ""),
                choice("security", "Security", "explicit FTPS (AUTH TLS)", "", "",
                        "plain FTP", "explicit FTPS (AUTH TLS)", "implicit FTPS (990)"),
                choice("auth", "Authentication", "username+password", "", "", "anonymous", "username+password"),
                txt("user", "Username", "", "", "auth=username+password"),
                pwd("password", "Password", "", "auth=username+password"),
                txt("dir", "Remote directory", "", "Created if missing", ""),
                insecure("security=explicit FTPS (AUTH TLS)|implicit FTPS (990)"))));

        // 9. SFTP / SCP
        t.add(new Type("sftp", "SFTP / SCP over SSH",
                "The server's host key fingerprint is shown for confirmation the first time and is checked on "
                        + "every later connection, before any credentials are sent.", false, list(
                txt("host", "Host", "", "", ""),
                txt("port", "Port", "22", "", ""),
                choice("protocol", "Protocol", "sftp", "", "", "sftp", "scp"),
                txt("user", "Username", "", "", ""),
                choice("auth", "Authentication", "password", "", "", "password", "private key"),
                pwd("password", "Password", "", "auth=password"),
                multi("keyText", "Private key (PEM text)", "Paste the key, or choose a file below", true, "auth=private key"),
                file("keyFile", "Private key file", "Used when no key text is pasted", "auth=private key"),
                pwd("passphrase", "Key passphrase", "Optional", "auth=private key"),
                txt("dir", "Remote directory", "", "Created recursively (SFTP only)", ""))));

        // 10. Network share
        t.add(new Type("share", "Network share / mounted folder (SMB, NFS, UNC)",
                "Writes into a folder: \\\\server\\share\\dir (Windows UNC) or a mounted path such as "
                        + "/mnt/share, /Volumes/share or Z:\\. SMB/NFS authentication is handled by the operating "
                        + "system (mapped drive, mount, Windows session), not by BurpShot.", false, list(
                txt("path", "Folder path", "", "Absolute path; created if missing", ""))));

        // 11. Local folder
        t.add(new Type("local", "Local folder",
                "Writes into a folder on this computer.", false, list(
                txt("path", "Folder path", "", "Absolute path; created if missing", ""))));
        return t;
    }

    private static List<Field> list(Field... f) {
        List<Field> l = new ArrayList<>();
        Collections.addAll(l, f);
        return l;
    }
}
