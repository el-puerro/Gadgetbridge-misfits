package nodomain.freeyourgadget.gadgetbridge.service.devices.misfits;

import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;

public class MisfitsDeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MisfitsDeviceSupport.class);

    private static UUID bleUuid(int s) {
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", s));
    }

    // Alert Notification Service
    private static final UUID UUID_ANS_UNREAD_STATUS = bleUuid(0x2A45);
    private static final UUID UUID_ANS_NEW_ALERT     = bleUuid(0x2A46);

    // Media Control Service
    private static final UUID UUID_SERVICE_MCS   = bleUuid(0x1848);
    private static final UUID UUID_MCS_TITLE     = bleUuid(0x2B97);
    private static final UUID UUID_MCS_DURATION  = bleUuid(0x2B98);
    private static final UUID UUID_MCS_POSITION  = bleUuid(0x2B99);
    private static final UUID UUID_MCS_STATE     = bleUuid(0x2BA3);
    private static final UUID UUID_MCS_ARTIST    =
        UUID.fromString("6d697366-6974-7300-a000-000000000001"); // custom

    private static final int ANS_NUM_CATEGORIES = 10;
    private static final int ANS_CATEGORY_ALL = 0xFF;
    private static final int ANS_TEXT_MAX = 18;
    private static final int MCS_STR_MAX = 64;

    /** Notification-ID -> ANS-Kategorie; daraus ergibt sich der Ungelesen-Zaehler. */
    private final Map<Integer, Integer> unreadById = new HashMap<>();

    public MisfitsDeviceSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_CURRENT_TIME);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);
        addSupportedService(GattService.UUID_SERVICE_ALERT_NOTIFICATION);
        addSupportedService(UUID_SERVICE_MCS);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));
        writeCurrentTime(builder);
        writeAllUnread(builder); // Uhr nach Reboot/Reconnect auf Stand bringen
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        return builder;
    }

    // ---------------------------------------------------------------- Zeit (CTS)

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("set time");
            writeCurrentTime(builder);
            builder.queue();
        } catch (IOException e) {
            LOG.error("failed to set time", e);
        }
    }

    private void writeCurrentTime(TransactionBuilder builder) {
        BluetoothGattCharacteristic chr = getCharacteristic(GattCharacteristic.UUID_CHARACTERISTIC_CURRENT_TIME);
        if (chr == null) {
            LOG.warn("current time characteristic not found");
            return;
        }
        byte[] payload = buildCurrentTimePayload();
        LOG.info("writing CTS payload, {} bytes", payload.length);
        builder.write(chr, payload);
    }

    private byte[] buildCurrentTimePayload() {
        GregorianCalendar c = new GregorianCalendar();

        int year = c.get(Calendar.YEAR);
        int dayOfWeek = c.get(Calendar.DAY_OF_WEEK) - 1;
        if (dayOfWeek == 0) {
            dayOfWeek = 7;
        }
        int fractions256 = c.get(Calendar.MILLISECOND) * 256 / 1000;

        return new byte[]{
            (byte) (year & 0xFF),
            (byte) ((year >> 8) & 0xFF),
            (byte) (c.get(Calendar.MONTH) + 1),
            (byte) c.get(Calendar.DAY_OF_MONTH),
            (byte) c.get(Calendar.HOUR_OF_DAY),
            (byte) c.get(Calendar.MINUTE),
            (byte) c.get(Calendar.SECOND),
            (byte) dayOfWeek,
            (byte) fractions256,
            (byte) 0x01,
        };
    }

    // ------------------------------------------------- Benachrichtigungen (ANS)

    @Override
    public void onNotification(NotificationSpec spec) {
        int cat = categoryFor(spec.getType());
        Integer oldCat;
        synchronized (unreadById) {
            oldCat = unreadById.put(spec.getId(), cat);
        }

        try {
            TransactionBuilder builder = performInitialized("notification");

            // Zaehler: Kategorie aktualisieren (ggf. auch die alte, falls sie gewechselt hat)
            writeUnread(builder, cat);
            if (oldCat != null && oldCat != cat) {
                writeUnread(builder, oldCat);
            }

            // "Letzter Alert"-Text: Titel, sonst Absender
            String text = firstNonEmpty(spec.getTitle(), spec.getSender());
            byte[] t = clipUtf8(text, ANS_TEXT_MAX);
            byte[] payload = new byte[2 + t.length];
            payload[0] = (byte) cat;
            payload[1] = 1;
            System.arraycopy(t, 0, payload, 2, t.length);
            write(builder, UUID_ANS_NEW_ALERT, payload);

            builder.queue();
        } catch (IOException e) {
            LOG.error("failed to send notification", e);
        }
    }

    @Override
    public void onDeleteNotification(int id) {
        Integer cat;
        synchronized (unreadById) {
            cat = unreadById.remove(id);
        }
        if (cat == null) {
            return;
        }
        try {
            TransactionBuilder builder = performInitialized("delete notification");
            writeUnread(builder, cat);
            builder.queue();
        } catch (IOException e) {
            LOG.error("failed to update unread count", e);
        }
    }

    private int countFor(int category) {
        int n = 0;
        synchronized (unreadById) {
            for (int c : unreadById.values()) {
                if (c == category) {
                    n++;
                }
            }
        }
        return Math.min(n, 255);
    }

    private void writeUnread(TransactionBuilder builder, int category) {
        write(builder, UUID_ANS_UNREAD_STATUS,
            new byte[]{(byte) category, (byte) countFor(category)});
    }

    private void writeAllUnread(TransactionBuilder builder) {
        write(builder, UUID_ANS_UNREAD_STATUS, new byte[]{(byte) ANS_CATEGORY_ALL, 0});
        for (int cat = 0; cat < ANS_NUM_CATEGORIES; cat++) {
            if (countFor(cat) > 0) {
                writeUnread(builder, cat);
            }
        }
    }

    /**
     * ANS-Kategorien: 0 Simple, 1 Email, 2 News, 3 Call, 4 Missed Call, 5 SMS/MMS,
     * 6 Voice Mail, 7 Schedule, 8 High Prioritized, 9 Instant Message.
     * Die Namen in NotificationType bitte gegen deine Gadgetbridge-Version pruefen.
     */
    private static int categoryFor(NotificationType type) {
        if (type == null) {
            return 0;
        }
        switch (type.getGenericType()) {
            case "generic_email":    return 1;
            case "generic_sms":      return 5;
            case "generic_calendar": return 7;
            case "generic_chat":     return 9;
            default:                 return 0;
        }
    }

    // ------------------------------------------------------------ Musik (MCS)

    @Override
    public void onSetMusicInfo(MusicSpec spec) {
        try {
            TransactionBuilder builder = performInitialized("music info");
            write(builder, UUID_MCS_TITLE, stringPayload(spec.getTrack()));
            write(builder, UUID_MCS_ARTIST, stringPayload(spec.getArtist()));
            // MusicSpec.duration ist in Sekunden, MCS erwartet 1/100 s; 0xFFFFFFFF = unbekannt
            write(builder, UUID_MCS_DURATION, int32le(spec.getDuration() > 0 ? spec.getDuration() * 100 : -1));
            builder.queue();
        } catch (IOException e) {
            LOG.error("failed to send music info", e);
        }
    }

    @Override
    public void onSetMusicState(MusicStateSpec spec) {
        int state;
        switch (spec.getState()) {
            case MusicStateSpec.STATE_PLAYING: state = 1; break;
            case MusicStateSpec.STATE_PAUSED:  state = 2; break;
            default:                           state = 0; break; // stopped/unknown -> inactive
        }
        try {
            TransactionBuilder builder = performInitialized("music state");
            // Reihenfolge wichtig: erst Position, dann Status. Die Uhr friert die Position
            // beim Statuswechsel ein und rechnet waehrend "Playing" selbst weiter.
            write(builder, UUID_MCS_POSITION, int32le(spec.getPosition() >= 0 ? spec.getPosition() * 100 : -1));
            write(builder, UUID_MCS_STATE, new byte[]{(byte) state});
            builder.queue();
        } catch (IOException e) {
            LOG.error("failed to send music state", e);
        }
    }

    // ----------------------------------------------------------------- Helfer

    private void write(TransactionBuilder builder, UUID uuid, byte[] payload) {
        BluetoothGattCharacteristic chr = getCharacteristic(uuid);
        if (chr == null) {
            LOG.warn("characteristic {} not found", uuid);
            return;
        }
        chr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        builder.write(chr, payload);
    }

    /** UTF-8, auf MCS_STR_MAX gekuerzt. Leerer String -> ein 0-Byte (die Uhr liest es als leer). */
    private static byte[] stringPayload(String s) {
        byte[] b = clipUtf8(s, MCS_STR_MAX);
        return b.length == 0 ? new byte[]{0} : b;
    }

    private static byte[] int32le(int v) {
        return new byte[]{
            (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)
        };
    }

    /** Kuerzt auf max Bytes, ohne ein UTF-8-Zeichen zu zerreissen. */
    private static byte[] clipUtf8(String s, int max) {
        if (s == null) {
            return new byte[0];
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) {
            return b;
        }
        int n = max;
        while (n > 0 && (b[n] & 0xC0) == 0x80) {
            n--;
        }
        return Arrays.copyOf(b, n);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b != null ? b : "";
    }
}
