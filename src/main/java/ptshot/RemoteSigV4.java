package ptshot;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4, header-based ("Authorization" header), as used by S3 and GCS interop. */
final class RemoteSigV4 {
    private RemoteSigV4() {}

    static final String ALGORITHM = "AWS4-HMAC-SHA256";

    /**
     * @param canonicalUri   URI path exactly as sent on the wire (already RFC 3986 encoded once, as S3 requires)
     * @param canonicalQuery query string with names/values encoded and sorted by name ("" if none)
     * @param headers        headers to sign (any case); values are trimmed and inner runs of spaces collapsed
     * @param payloadHash    lowercase hex SHA-256 of the body (or "UNSIGNED-PAYLOAD")
     * @param amzDate        yyyyMMdd'T'HHmmss'Z'
     * @return the complete Authorization header value
     */
    static String authorization(String method, String canonicalUri, String canonicalQuery,
                                Map<String, String> headers, String payloadHash,
                                String accessKey, String secretKey, String region, String service,
                                String amzDate) {
        TreeMap<String, String> h = new TreeMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            h.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue().trim().replaceAll(" +", " "));
        }
        StringBuilder canonHeaders = new StringBuilder();
        StringBuilder signed = new StringBuilder();
        for (Map.Entry<String, String> e : h.entrySet()) {
            canonHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (signed.length() > 0) signed.append(';');
            signed.append(e.getKey());
        }
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonHeaders + "\n" + signed + "\n" + payloadHash;

        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + RemoteUtil.hex(RemoteUtil.sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmac(kSecret, date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = RemoteUtil.hex(hmac(kSigning, stringToSign));
        for (byte[] k : new byte[][]{kSecret, kDate, kRegion, kService, kSigning}) Arrays.fill(k, (byte) 0);

        return ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signed + ", Signature=" + signature;
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }
}
