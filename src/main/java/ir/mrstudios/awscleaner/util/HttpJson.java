package ir.mrstudios.awscleaner.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;

public final class HttpJson {
    private HttpJson() {}

    public static RequestBody body(byte[] bytes, String contentType, boolean oneShot) {
        return new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get(contentType); }
            @Override public long contentLength() { return bytes.length; }
            @Override public boolean isOneShot() { return oneShot; }
            @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(bytes); }
        };
    }

    public static JsonObject object(String json, String source) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(source + " returned invalid JSON");
        }
    }

    public static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return "";
    }
}
