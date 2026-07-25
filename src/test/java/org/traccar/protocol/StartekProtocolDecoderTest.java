package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StartekProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new StartekProtocolDecoder(null));

        verifyAttribute(decoder, text(
                "&&L171,868825064282040,000,0,,241209063302,A,13.809656,100.558255,14,0.9,0,0,67,1560,520|4|A418|008AAC3F,31,000000BD,02,00,04E3|0171|0000|0000,131,,,,3100  1  61000541  10800  ?FE\r\n"),
                Position.KEY_DRIVER_UNIQUE_ID, "3100  1  61000541  10800  ?");

        verifyAttributes(decoder, text(
                "&&\\546,865491061145970,710,T1,0.0,0.0,0.0,0.0,0,0,0,0,0.0,0,0.0,0,F0,0.0,0.0,0.0,0.0,0.0,0,0.0,0,0,0,0,0,1,0,0.0,00,0.0,0.0,0.0,0\r\n",
                "T2,0.000,0.0,9223372036854775808.8,9223372036854775808.8,4294967295,4294967295,0,429496729,0,0,0,0,0,0,0,0,0,0,0.0,21474836.0,9223372036854775808.8,0,00,0,0,0.00\r\n",
                "T5,0,0,0,0,0,0,0,0,0,0,0,0,0.0,0.0,*,*,0\r\n",
                "T6,00,03,00,1F,1F,1F,0E,03,00,00,00,00,1F,1F\r\n",
                "T7,0,0,0,429496729,21474836.000,429496729,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0,0,0.000\r\n",
                "Tx,*,*,*,*,*,0.0,0,0,0,0,-125,0,0,0,0,0,0,0\r\n",
                "F3\r\n"));

        verifyPosition(decoder, text(
                "&&l141,863911061945394,000,0,,230918072531,A,22.678598,114.045970,26,0.6,0,0,74,2286304571,460|0|249F|00001093,20,001C,00,00,04A7|019C|0000|0000,1,C0\r\n"));

        verifyAttribute(decoder, text(
                "&&s148,868703050178631,000,37,,230704040211,A,22.678565,114.046011,31,0.5,0,339,77,8,460|0|249F|0AC2620D,27,0000001D,02,00,04F2|01A1|0000|0000,129,,,,949037\r\n"),
                Position.KEY_HOURS, 9490000L);

        verifyAttribute(decoder, text(
                "&&x164,869926040743375,000,0,,220705205955,A,33.326001,44.445318,10,1.2,0,57,8,925,418|40|038C|000083CD,31,00000015,00,00,0016|016A|0000|0000,1,,,686|33||44|99|14|124|11|8D\r\n"),
                Position.KEY_FUEL_CONSUMPTION, 1.1);

        verifyAttribute(decoder, text(
                "&&R187,860294046453690,000,0,,220105160656,A,22.994986,72.499711,15,0.9,2,222,55,121135784,404|98|147B|0000376A,24,0000001F,02,00,052E|01A3|0000|0000,1,010000|020000,,853|6|10|105|73|41|125|34|52\r\n"),
                Position.KEY_FUEL, null);

        verifyPosition(decoder, text(
                "&&o142,860262050066062,000,27,,211111070826,V,28.653435,-106.077455,0,0.0,0,151,1412,918,0|0|4708|01402D19,6,0000001A,02,00,04C0|016C|0000|0000,1,,,BB\r\n"));

        verifyPosition(decoder, text(
                "&&W149,865429043319537,000,0,,211103013512,A,22.679003,114.045085,16,1.1,0,271,76,109075,460|0|249F|000010C5,19,0000003E,00,00,0A57|0168|0000|0000,1,0100000C\r\n"));

        verifyAttribute(decoder, text(
                "&&:23,860262050015424,129,OKA2\r\n"),
                Position.KEY_RESULT, "OK");

        verifyPosition(decoder, text(
                "&&X152,861157040151686,000,18,,210907163833,A,10.232715,-67.880423,11,1.4,0,275,437,34804,734|2|3EE4|00579406,28,00000015,00,00,0000|017D|0000|0000,1,010000,,9A\r\n"));

        verifyPosition(decoder, text(
                "&&o125,861157040554384,000,0,,210702235150,A,27.263505,153.037061,11,1.2,0,0,31,5125,505|1|7032|8C89802,20,0000002D,00,00,01E2|019DF0\r\n"));

        verifyAttribute(decoder, text(
                "&&a152,860262050010565,000,53,8F5300,210528015706,A,-38.229746,145.043446,6,1.5,0,285,84,2102994,505|1|306E|082D6101,31,0000003D,02,02,04C0|01A0|0000|0000,1,,DC\r\n"),
                Position.KEY_DRIVER_UNIQUE_ID, "8F5300");

        verifyPosition(decoder, text(
                "&&>141,860262050010565,000,36,,210407094323,V,-38.229711,145.043161,0,0.0,0,0,0,14222,505|1|306E|082D6115,24,00000039,00,00,04C0|0164|0000|0000,1,,41\r\n"));

        verifyPosition(decoder, text(
                "&&A147,021104023195429,000,0,,180106093046,A,22.646430,114.065730,8,0.9,54,86,76,326781,460|0|27B3|0EA7,27,0000000F,02,01,04E2|018C|01C8|0000,1,0104B0,01013D|02813546\r\n"));

        verifyPosition(decoder, text(
                "&&y139,860262050009146,000,0,,210323131512,A,22.678655,114.046223,14,1.1,0,231,71,5,460|0|249F|000010C5,28,0000003D,00,00,0493|0199|0000|0000,1,,33\r\n"));

    }

    @Test
    public void testDecodeEvIgnitionOn() throws Exception {

        var decoder = injectEvDecoder("ev");

        // Input hex 02 → bit 1 = 1 → ignition on → ALARM_POWER_ON.
        // Slot 6 flags = 67 (0b01000011) → gear D (bits 6-7 = 01) + headlight + charging.
        // OBD block (EV mapping):
        //   RPM=3000, batteryPower raw=1500 → (1500-1000)/10 = 50.0 kW,
        //   HV raw=4000 → 400.0 V, remainingPower=30, ODO=15234 (overrides main odo=925),
        //   flags=67, batteryTemp raw=75 → 35°C, powerConsumption=12, SoC=85%.
        String frame = "&&x164,868825064282040,000,0,,220705205955,A,33.326001,44.445318,10,1.2,0,57,8,"
                + "925,418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,"
                + "3000|1500|4000|30|15234|67|75|12|85%FE\r\n";

        verifyAttribute(decoder, text(frame), Position.KEY_RPM, 3000);
        verifyAttribute(decoder, text(frame), "batteryPower", 50.0);
        verifyAttribute(decoder, text(frame), "HV", 400.0);
        verifyAttribute(decoder, text(frame), "remainingPower", 30);
        verifyAttribute(decoder, text(frame), Position.KEY_OBD_ODOMETER, 15234L);
        verifyAttribute(decoder, text(frame), "soc", 85);
        verifyAttribute(decoder, text(frame), "batteryTemp", 35);
        verifyAttribute(decoder, text(frame), "powerConsumption", 12);

        // Slot 6 (flags=67) → bit0 charge, bit1 headlight, bits 6-7 = gear D.
        verifyAttribute(decoder, text(frame), Position.KEY_CHARGE, true);
        verifyAttribute(decoder, text(frame), "headlight", true);
        verifyAttribute(decoder, text(frame), "gearPositions", "D");
        verifyAttribute(decoder, text(frame), "turnLeft", null);
        verifyAttribute(decoder, text(frame), "turnRight", null);
        verifyAttribute(decoder, text(frame), "parkingBrake", null);
        verifyAttribute(decoder, text(frame), "hazard", null);

        // ICE keys must NOT be present for EV.
        verifyAttribute(decoder, text(frame), Position.KEY_ENGINE_LOAD, null);
        verifyAttribute(decoder, text(frame), Position.KEY_FUEL, null);
        verifyAttribute(decoder, text(frame), Position.KEY_THROTTLE, null);
        verifyAttribute(decoder, text(frame), Position.KEY_COOLANT_TEMP, null);
        verifyAttribute(decoder, text(frame), Position.KEY_FUEL_CONSUMPTION, null);

        verifyAttribute(decoder, text(frame), Position.KEY_IGNITION, true);
        verifyAttribute(decoder, text(frame), "poweron", true);
        verifyAttribute(decoder, text(frame), "poweroff", null);
    }

    @Test
    public void testDecodeEvIgnitionOff() throws Exception {

        var decoder = injectEvDecoder("evcar");

        // input=00 → ignition off → ALARM_POWER_OFF.
        // flags=0 → all boolean flags cleared, gearPositions="N" (bits 6-7 = 00).
        // batteryPower raw=1000 → 0.0 kW (idle/parked).
        String frame = "&&x164,868825064282040,000,0,,220705205955,A,33.326001,44.445318,10,1.2,0,57,8,"
                + "925,418|40|038C|000083CD,31,00000015,00,00,0016|016A|0000|0000,1,,,"
                + "3000|1000|4000|30|15234|0|75|12|85%FE\r\n";

        verifyAttribute(decoder, text(frame), Position.KEY_IGNITION, false);
        verifyAttribute(decoder, text(frame), "poweroff", true);
        verifyAttribute(decoder, text(frame), "poweron", null);

        verifyAttribute(decoder, text(frame), "batteryPower", 0.0);
        verifyAttribute(decoder, text(frame), "gearPositions", "N");

        verifyAttribute(decoder, text(frame), Position.KEY_CHARGE, null);
        verifyAttribute(decoder, text(frame), "headlight", null);
        verifyAttribute(decoder, text(frame), "turnLeft", null);
        verifyAttribute(decoder, text(frame), "turnRight", null);
        verifyAttribute(decoder, text(frame), "parkingBrake", null);
        verifyAttribute(decoder, text(frame), "hazard", null);
    }

    @Test
    public void testDecodeEvObdOdometerSeparateFromMain() throws Exception {

        var decoder = injectEvDecoder("ev");

        // EV slot 5 goes to KEY_OBD_ODOMETER — main KEY_ODOMETER (925) is never overwritten.
        String frame = "&&x164,868825064282040,000,0,,220705205955,A,33.326001,44.445318,10,1.2,0,57,8,"
                + "925,418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,"
                + "3000|1500|4000|30|15234|67|75|12|85%FE\r\n";

        verifyAttribute(decoder, text(frame), Position.KEY_ODOMETER, 925L);
        verifyAttribute(decoder, text(frame), Position.KEY_OBD_ODOMETER, 15234L);
    }

    @Test
    public void testDecodeEvBatteryPowerRegen() throws Exception {

        var decoder = injectEvDecoder("ev");

        // batteryPower raw=700 → (700-1000)/10 = -30.0 kW (regenerative braking).
        // flags=64 → gear D only.
        String frame = "&&x164,868825064282040,000,0,,220705205955,A,33.326001,44.445318,10,1.2,3,180,8,"
                + "925,418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,"
                + "3000|700|4000|30|15240|64|75|12|85%FE\r\n";

        verifyAttribute(decoder, text(frame), "batteryPower", -30.0);
        verifyAttribute(decoder, text(frame), "gearPositions", "D");
    }

    @Test
    public void testDecodeEvGearReverseWithParkingBrake() throws Exception {

        var decoder = injectEvDecoder("ev");

        // flags=144 (0b10010000) → gear R (bits 6-7 = 10) + parkingBrake (bit 4).
        String frame = "&&x164,868825064282040,000,0,,220705205955,A,33.326001,44.445318,10,1.2,0,0,8,"
                + "925,418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,"
                + "0|1000|4000|0|15240|144|75|12|85%FE\r\n";

        verifyAttribute(decoder, text(frame), "gearPositions", "R");
        verifyAttribute(decoder, text(frame), "parkingBrake", true);
        verifyAttribute(decoder, text(frame), Position.KEY_CHARGE, null);
    }

    @Test
    public void testDecodeEvGearReservedNotSet() throws Exception {

        var decoder = injectEvDecoder("ev");

        // flags=192 (bits 6-7 = 11) → reserved gear code → gearPositions must NOT be set.
        String frame = "&&x164,868825064282040,000,0,,220705205955,A,33.326001,44.445318,10,1.2,0,0,8,"
                + "925,418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,"
                + "0|1000|4000|0|15240|192|75|12|85%FE\r\n";

        verifyAttribute(decoder, text(frame), "gearPositions", null);
    }

    @Test
    public void testDecodeInt3TriggerMapsToDriverBehavior() throws Exception {

        // Event codes 5 (Input3 active) and 6 (Input3 inactive) — emitted by iStartek
        // when INT3 is toggled (HW wiring for headlight / turn / parking brake / gear change).
        // Both should now produce alarm = "driverBehavior", not "door".
        var decoder = inject(new StartekProtocolDecoder(null));

        String frameActive = "&&x164,868825064282040,000,5,,220705205955,A,33.326001,44.445318,10,1.2,0,0,8,"
                + "925,418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,"
                + "0|0|0|0|0|0|75|0|85%FE\r\n";
        verifyAttribute(decoder, text(frameActive), Position.KEY_ALARM, Position.ALARM_DRIVER_BEHAVIOR);

        String frameInactive = "&&x164,868825064282040,000,6,,220705205955,A,33.326001,44.445318,10,1.2,0,0,8,"
                + "925,418|40|038C|000083CD,31,00000015,00,00,0016|016A|0000|0000,1,,,"
                + "0|0|0|0|0|0|75|0|85%FE\r\n";
        verifyAttribute(decoder, text(frameInactive), Position.KEY_ALARM, Position.ALARM_DRIVER_BEHAVIOR);
    }

    private StartekProtocolDecoder injectEvDecoder(String category) throws Exception {
        var decoder = inject(new StartekProtocolDecoder(null));
        var evDevice = mock(Device.class);
        when(evDevice.getId()).thenReturn(1L);
        when(evDevice.getCategory()).thenReturn(category);
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(new Config());
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(evDevice);
        decoder.setCacheManager(cacheManager);
        return decoder;
    }

}
