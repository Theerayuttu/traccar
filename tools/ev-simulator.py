#!/usr/bin/env python3
"""
Startek EV Frame Simulator (V2.8)
---------------------------------
Sends test EV position frames to a Traccar server running the modified
StartekProtocolDecoder. Encoding matches firmware V2.8 spec:

  slot 2 (empty)                                   # batteryPower moved to slot 8
  slot 3 (HV)                    = V / 4           # factor 4, range 0..1016 V
  slot 4 (remainingRange high)   = km / 100        # 0..254 hundreds-digit
  slot 5 (remainingRange low)    = km % 100        # 0..99 last-two-digits
                                                   # (slot 5 is NO LONGER OBD odometer)
  slot 7 (batteryTemp)           = C + 40
  slot 8 (batteryPower)          = kW * 10 + 10000 # 16-bit, 0.1 kW resolution
                                                   # 0 = no data (skip attribute)

Prerequisite on the Traccar server:
  1. Register a device with the same IMEI as `--imei`.
  2. Set that device's Category = "ev" (or "evcar", "evtruck", ...).
  3. Make sure Startek TCP port (default 5222) is reachable.

Examples:
  python ev-simulator.py                                    # all scenarios
  python ev-simulator.py --host 10.0.0.5 --imei 868825...   # custom target
  python ev-simulator.py --only 3                           # one scenario
"""
import socket
import argparse
import time
from datetime import datetime, timezone


DEFAULT_HOST = "18.136.224.93"
DEFAULT_PORT = 5222
DEFAULT_IMEI = "868825064282040"


def build_startek_frame(idx: str, imei: str, msg_type: str, content: str) -> str:
    """
    Build a complete Startek frame with the correct length header.

    Wire format:
        &&<idx><length>,<imei>,<type>,<content><checksum>\r\n

    Length rule (from StartekFrameDecoder.java):
        length_value = len(body) - 3
    where body = <imei>,<type>,<content><checksum>\r\n
    """
    checksum = "FE"
    body = f"{imei},{msg_type},{content}{checksum}\r\n"
    length_value = len(body) - 3
    return f"&&{idx}{length_value},{body}"


GEAR_CODES = {"N": 0, "D": 1, "R": 2}


def encode_flags(
        charging: bool = False,
        headlight: bool = False,
        turn_left: bool = False,
        turn_right: bool = False,
        parking_brake: bool = False,
        hazard: bool = False,
        gear: str = "N") -> int:
    """
    Build the slot-6 bitmask (spec v1.3 ข้อ 5.1):
      bit 0 = charging, bit 1 = headlight, bit 2 = turn_left, bit 3 = turn_right,
      bit 4 = parking_brake, bit 5 = hazard, bits 6-7 = gear (N=0, D=1, R=2).
    """
    flags = 0
    if charging:
        flags |= 1 << 0
    if headlight:
        flags |= 1 << 1
    if turn_left:
        flags |= 1 << 2
    if turn_right:
        flags |= 1 << 3
    if parking_brake:
        flags |= 1 << 4
    if hazard:
        flags |= 1 << 5
    if gear not in GEAR_CODES:
        raise ValueError(f"gear must be one of {list(GEAR_CODES)}, got {gear!r}")
    flags |= GEAR_CODES[gear] << 6
    return flags


def encode_battery_power(kw: float) -> int:
    """V2.8: raw = kW * 10 + 10000 (16-bit, 0.1 kW resolution). Clamped to 1..65534.
    raw = 0 means no data — pass kw=None (or use dedicated no-data path) to simulate."""
    raw = int(round(kw * 10)) + 10000
    if raw < 1 or raw > 65534:
        raise ValueError(f"batteryPower {kw} kW out of range (-999.9..+5553.4)")
    return raw


def encode_hv(volts: float) -> int:
    """V2.4: raw = V / 4 (factor 4). Clamped to 0..254."""
    raw = int(round(volts / 4))
    if raw < 0 or raw > 254:
        raise ValueError(f"HV {volts} V out of range (0..1016)")
    return raw


def encode_remaining_range(km: int) -> tuple[int, int]:
    """V2.6: split km into (slot4 = km / 100, slot5 = km % 100). Max 25,499 km."""
    if km < 0 or km > 25499:
        raise ValueError(f"remainingRange {km} km out of range (0..25499)")
    return km // 100, km % 100


def build_ev_position_content(
        timestamp: datetime,
        ignition: bool,
        flags: int,
        motor_rpm: int,
        battery_power_kw: float,
        battery_hv_v: float,
        remaining_range_km: int,
        battery_temp_c: int,
        soc: int,
        lat: float = 13.809656,
        lon: float = 100.558255) -> str:
    """
    Compose the position-payload (type 000) content string. Values are placed
    into slots in the order defined by PATTERN_POSITION so the modified decoder
    interprets them as EV attributes.
    """
    date_str = timestamp.strftime("%y%m%d%H%M%S")
    input_hex = "02" if ignition else "00"       # bit 1 controls KEY_IGNITION

    battery_power_raw = encode_battery_power(battery_power_kw)
    battery_hv_raw = encode_hv(battery_hv_v)
    battery_temp_raw = battery_temp_c + 40
    range_hi, range_lo = encode_remaining_range(remaining_range_km)

    # Extended OBD block (9 pipe-separated slots, always trailing with SoC%)
    # V2.8: slot 2 empty, slot 8 carries batteryPower
    obd = (
        f"{motor_rpm}||{battery_hv_raw}|{range_hi}|{range_lo}|"
        f"{flags}|{battery_temp_raw}|{battery_power_raw}|{soc}%"
    )

    # Structure per PATTERN_POSITION:
    # event, eventData, datetime, valid, lat, lon, sats, hdop,
    # speed, course, alt, main_odo, mcc|mnc|lac|cid, rssi, status,
    # input(hex), output(hex), power|batt|adc|adc, extCount, fuel, temp, <OBD>
    return (
        f"0,,{date_str},A,{lat},{lon},10,1.2,0,57,8,"
        f"925,418|40|038C|000083CD,31,00000015,{input_hex},00,"
        f"04E2|016A|0000|0000,1,,,{obd}"
    )


def send_frame(host: str, port: int, frame: str, label: str, expected: str) -> None:
    """Open a fresh TCP connection, send one frame, close."""
    displayed = frame.replace("\r\n", "\\r\\n")
    print(f"-> {label}")
    print(f"   expected decode: {expected}")
    print(f"   {len(frame)} bytes: {displayed}")
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(5)
            s.connect((host, port))
            s.sendall(frame.encode("ascii"))
            s.settimeout(1.0)
            try:
                reply = s.recv(1024)
                if reply:
                    print(f"   <- reply: {reply!r}")
            except socket.timeout:
                pass
        print("   OK\n")
    except OSError as exc:
        print(f"   FAILED: {exc}\n")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Send simulated Startek EV frames (V2.8) to a Traccar server.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--host", default=DEFAULT_HOST,
                        help=f"Server host (default {DEFAULT_HOST})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"Startek TCP port (default {DEFAULT_PORT})")
    parser.add_argument("--imei", default=DEFAULT_IMEI,
                        help=f"Device IMEI (default {DEFAULT_IMEI})")
    parser.add_argument("--delay", type=float, default=2.0,
                        help="Seconds between frames (default 2.0)")
    parser.add_argument("--only", type=int, choices=[1, 2, 3, 4, 5], default=None,
                        help="Send only scenario 1-5 (default: all)")
    args = parser.parse_args()

    now = datetime.now(timezone.utc)

    print("=== Startek EV Simulator (V2.8) ===")
    print(f"Target : {args.host}:{args.port}")
    print(f"IMEI   : {args.imei}   (device category must start with 'ev')")
    print(f"Time   : {now.isoformat()}")
    print()

    # Scenarios tuned for Dongfeng EV (HV ~590 V, real-world values from V2.8 log)
    scenarios = [
        (1, "Parked | gear N + parking brake | +3.4 kW idle | HV 588 V | 30 km",
         "batteryPower=3.4, HV=588, remainingRange=30, parkingBrake=true, gear=N",
         dict(
            ignition=False,
            flags=encode_flags(parking_brake=True, gear="N"),
            motor_rpm=0, battery_power_kw=3.4, battery_hv_v=588.0,
            remaining_range_km=30, battery_temp_c=34, soc=30)),

        (2, "Driving (long-range) | gear D + headlight | +75.7 kW | 328 km",
         "batteryPower=75.7, HV=588, remainingRange=328 (slot4=3+slot5=28), headlight=true, gear=D",
         dict(
            ignition=True,
            flags=encode_flags(headlight=True, gear="D"),
            motor_rpm=3000, battery_power_kw=75.7, battery_hv_v=588.0,
            remaining_range_km=328, battery_temp_c=36, soc=90)),

        (3, "Regen braking | gear D | -20.4 kW",
         "batteryPower=-20.4, HV=592, gear=D",
         dict(
            ignition=True,
            flags=encode_flags(gear="D"),
            motor_rpm=1800, battery_power_kw=-20.4, battery_hv_v=592.0,
            remaining_range_km=325, battery_temp_c=36, soc=29)),

        (4, "Peak discharge | gear D + hazard | +150 kW",
         "batteryPower=150.0, HV=572, hazard=true, gear=D",
         dict(
            ignition=True,
            flags=encode_flags(hazard=True, gear="D"),
            motor_rpm=6500, battery_power_kw=150.0, battery_hv_v=572.0,
            remaining_range_km=250, battery_temp_c=42, soc=25)),

        (5, "DC fast charging | gear N | -148.3 kW (matches dashboard -143)",
         "batteryPower=-148.3, HV=600, charge=true, parkingBrake=true, gear=N",
         dict(
            ignition=False,
            flags=encode_flags(charging=True, parking_brake=True, gear="N"),
            motor_rpm=0, battery_power_kw=-148.3, battery_hv_v=600.0,
            remaining_range_km=85, battery_temp_c=38, soc=75)),
    ]

    for i, (num, label, expected, kwargs) in enumerate(scenarios):
        if args.only is not None and args.only != num:
            continue
        content = build_ev_position_content(timestamp=now, **kwargs)
        frame = build_startek_frame("x", args.imei, "000", content)
        send_frame(args.host, args.port, frame, f"[{num}] {label}", expected)
        if i < len(scenarios) - 1 and args.only is None:
            time.sleep(args.delay)

    print("Done. Open Traccar UI -> device details / API "
          "/api/positions?deviceId=... to verify EV attributes.")


if __name__ == "__main__":
    main()
