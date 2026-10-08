package app.wisprail.storage;

import app.wisprail.profile.OpenVpnSettings;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileDocument;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import app.wisprail.profile.VlessSettings;
import app.wisprail.profile.VpnSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Set;

/** One wire/storage codec; protocol polymorphism is explicitly discriminated. */
public final class JsonCodec {
  private static final Set<String> PROFILE_FIELDS =
      Set.of(
          "id",
          "revision",
          "name",
          "server",
          "port",
          "settings",
          "networks",
          "dns",
          "domains",
          "healthUrl",
          "secretRef");
  private static final Set<String> SECRET_FIELDS =
      Set.of("password", "privateKey", "privateKeyPassword", "tlsAuthKey", "tlsCryptKey", "uuid");
  private static final Set<String> OPENVPN_FIELDS =
      Set.of(
          "type",
          "transport",
          "username",
          "askPassword",
          "caCertificate",
          "clientCertificate",
          "cipher",
          "authDigest",
          "tlsServerName",
          "tlsKeyDirection");
  private static final Set<String> VLESS_FIELDS =
      Set.of("type", "security", "serverName", "publicKey", "shortId", "fingerprint", "flow");
  private static final Gson GSON =
      new GsonBuilder()
          .setStrictness(Strictness.STRICT)
          .disableJdkUnsafe()
          .registerTypeAdapter(
              Instant.class,
              new TypeAdapter<Instant>() {
                @Override
                public void write(JsonWriter out, Instant value) throws IOException {
                  out.value(value.toString());
                }

                @Override
                public Instant read(JsonReader in) throws IOException {
                  return Instant.parse(in.nextString());
                }
              }.nullSafe())
          .registerTypeAdapter(VpnSettings.class, new SettingsAdapter())
          .create();

  private JsonCodec() {}

  public static Gson gson() {
    return GSON;
  }

  /** Parses a bounded document, rejecting duplicate fields at every nesting level. */
  public static JsonElement parseStrict(String text, int maximumDepth) throws IOException {
    if (text.length() > 4 * 1024 * 1024 || maximumDepth < 1 || maximumDepth > 32) {
      throw new IOException("JSON превышает допустимый размер или глубину");
    }
    try (JsonReader reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      reader.setNestingLimit(maximumDepth);
      JsonElement result = readValue(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new IOException("Лишние данные после JSON");
      }
      return result;
    } catch (JsonParseException | IllegalStateException exception) {
      throw new IOException("Некорректный JSON");
    }
  }

  private static JsonElement readValue(JsonReader reader) throws IOException {
    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
      JsonObject object = new JsonObject();
      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (object.has(name)) {
          throw new IOException("JSON содержит повторяющееся поле");
        }
        object.add(name, readValue(reader));
      }
      reader.endObject();
      return object;
    }
    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
      JsonArray array = new JsonArray();
      reader.beginArray();
      while (reader.hasNext()) {
        array.add(readValue(reader));
      }
      reader.endArray();
      return array;
    }
    return JsonParser.parseReader(reader);
  }

  public static ProfileDocument readProfileDocument(JsonElement value) {
    JsonObject object = objectWithFields(value, Set.of("formatVersion", "profile"));
    requireInteger(object.get("formatVersion"), false);
    int version = object.get("formatVersion").getAsInt();
    if (version != ProfileDocument.VERSION) {
      throw new JsonParseException("Неподдержанный формат профиля Wisprail");
    }
    return new ProfileDocument(version, readProfile(object.get("profile")));
  }

  public static Profile readProfile(JsonElement value) {
    JsonObject object = objectWithFields(value, PROFILE_FIELDS);
    for (String field : PROFILE_FIELDS) {
      JsonElement member = object.get(field);
      switch (field) {
        case "settings" -> {
          if (!member.isJsonObject()) {
            throw new JsonParseException("Настройки VPN должны быть объектом");
          }
        }
        case "revision", "port" -> requireInteger(member, field.equals("revision"));
        case "networks", "domains" -> {
          if (!member.isJsonArray()
              || member.getAsJsonArray().size() > ProfileValidator.MAX_ENTRIES) {
            throw new JsonParseException("Неверный формат или размер списка сетей или доменов");
          }
          member.getAsJsonArray().forEach(JsonCodec::requireString);
        }
        default -> requireString(member);
      }
    }
    return GSON.fromJson(object, Profile.class);
  }

  public static ProfileSecrets readSecrets(JsonElement value) {
    JsonObject object = objectWithFields(value, SECRET_FIELDS);
    object.asMap().values().forEach(JsonCodec::requireString);
    return GSON.fromJson(object, ProfileSecrets.class);
  }

  private static JsonObject objectWithFields(JsonElement value, Set<String> fields) {
    if (value == null
        || !value.isJsonObject()
        || !value.getAsJsonObject().keySet().equals(fields)) {
      throw new JsonParseException("JSON содержит неизвестные или отсутствующие поля");
    }
    return value.getAsJsonObject();
  }

  private static void requireString(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new JsonParseException("Ожидается строка в JSON");
    }
  }

  private static void requireInteger(JsonElement value, boolean longValue) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new JsonParseException("Ожидается целое число в JSON");
    }
    try {
      if (longValue) {
        value.getAsBigDecimal().longValueExact();
      } else {
        value.getAsBigDecimal().intValueExact();
      }
    } catch (ArithmeticException exception) {
      throw new JsonParseException("Число JSON выходит за допустимый диапазон");
    }
  }

  private static final class SettingsAdapter
      implements JsonSerializer<VpnSettings>, JsonDeserializer<VpnSettings> {
    @Override
    public JsonElement serialize(VpnSettings value, Type type, JsonSerializationContext context) {
      JsonObject object =
          switch (value) {
            case OpenVpnSettings settings -> context.serialize(settings).getAsJsonObject();
            case VlessSettings settings -> context.serialize(settings).getAsJsonObject();
          };
      object.addProperty("type", value.type().name());
      return object;
    }

    @Override
    public VpnSettings deserialize(
        JsonElement value, Type type, JsonDeserializationContext context) {
      if (!value.isJsonObject()) {
        throw new JsonParseException("Настройки протокола должны быть объектом");
      }
      JsonObject object = value.getAsJsonObject();
      if (!object.has("type")) {
        throw new JsonParseException("Не указан тип протокола");
      }
      requireString(object.get("type"));
      Set<String> fields =
          switch (object.get("type").getAsString()) {
            case "OPENVPN" -> OPENVPN_FIELDS;
            case "VLESS" -> VLESS_FIELDS;
            default -> throw new JsonParseException("Неподдержанный тип протокола");
          };
      objectWithFields(object, fields);
      for (String field : fields) {
        JsonElement member = object.get(field);
        if (field.equals("askPassword")) {
          if (!member.isJsonPrimitive() || !member.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("Ожидается логическое значение askPassword");
          }
        } else if (field.equals("tlsKeyDirection")) {
          requireInteger(member, false);
        } else {
          requireString(member);
        }
      }
      return switch (object.get("type").getAsString()) {
        case "OPENVPN" -> context.deserialize(object, OpenVpnSettings.class);
        case "VLESS" -> context.deserialize(object, VlessSettings.class);
        default -> throw new JsonParseException("Неподдержанный тип протокола");
      };
    }
  }
}
