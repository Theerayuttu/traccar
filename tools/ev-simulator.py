#!/usr/bin/env python3
"""
Startek EV Frame Simulator
--------------------------
Sends test EV position frames to a Traccar server that runs the modified
StartekProtocolDecoder. Useful for end-to-end verification before the real
EV hardware is available.

Prerequisite on the Traccar server:
  1. Register a device with the same IMEI as `--imei`.
  2. Set that device's Category = "ev" (or "evcar", "evtruck", ...).
  3. Make sure Startek TCP port (default 5222) is reachable.

Examples:
  # Localhost, default IMEI, 3 scenarios
  python ev-simulator.py

  # Custom server and IMEI
  python ev-simulator.py --host 10.0.0.5 --port 5222 --imei 868825064282040

  # Only send scenario 1
  python ev-simulator.py --only 1
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
    (Checksum is 2 hex chars; the protocol decoder does not verify its value.)
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
    Build the slot-6 bitmask (spec v1.1):
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


def build_ev_position_content(
        timestamp: datetime,
        ignition: bool,
        flags: int,
        motor_rpm: int,
        battery_power_kw: float,
        battery_hv_v: float,
        remaining_power: int,
        odo: int,
        battery_temp_c: int,
        power_consumption: int,
        soc: int,
        lat: float = 13.809656,
        lon: float = 100.558255) -> str:
    """
    Compose the position-payload (type 000) content string. Values are placed
    into slots in the order defined by PATTERN_POSITION so the modified decoder
    interprets them as EV attributes.

    Encoding rules (spec v1.1):
      slot 2 (Battery Power)  = (kW * 10) + 1000            # bias +1000, signed
      slot 3 (Battery HV)     = V * 10                      # precision 0.1
      slot 7 (Battery Temp)   = actual_C + 40               # bias +40
    """
    date_str = timestamp.strftime("%y%m%d%H%M%S")
    input_hex = "02" if ignition else "00"       # bit 1 controls KEY_IGNITION

    battery_power_raw = int(round(battery_power_kw * 10)) + 1000
    battery_hv_raw = int(round(battery_hv_v * 10))
    battery_temp_raw = battery_temp_c + 40

    # Extended OBD block (9 pipe-separated slots, always trailing with SoC%)
    obd = (
        f"{motor_rpm}|{battery_power_raw}|{battery_hv_raw}|{remaining_power}|{odo}|"
        f"{flags}|{battery_temp_raw}|{power_consumption}|{soc}%"
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


def send_frame(host: str, port: int, frame: str, label: str) -> None:
    """Open a fresh TCP connection, send one frame, close."""
    displayed = frame.replace("\r\n", "\\r\\n")
    print(f"->{label}")
    print(f"   {len(frame)} bytes: {displayed}")
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(5)
            s.connect((host, port))
            s.sendall(frame.encode("ascii"))
            # Startek server typically does not reply to a position frame,
            # but drain briefly in case it does (e.g. error / ack).
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
        description="Send simulated Startek EV frames to a Traccar server.",
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
    parser.add_argument("--only", type=int, choices=[1, 2, 3, 4], default=None,
                        help="Send only scenario 1, 2, 3, or 4 (default: all)")
    args = parser.parse_args()

    now = datetime.now(timezone.utc)

    print("=== Startek EV Simulator ===")
    print(f"Target : {args.host}:{args.port}")
    print(f"IMEI   : {args.imei}   (device category must start with 'ev')")
    print(f"Time   : {now.isoformat()}")
    print()

    scenarios = [
        (1, "Ignition ON  |  Gear D + Charging + Headlight  |  consume 50.0 kW", dict(
            ignition=True,
            flags=encode_flags(charging=True, headlight=True, gear="D"),
            motor_rpm=3000, battery_power_kw=50.0, battery_hv_v=400.5,
            remaining_power=30, odo=15234, battery_temp_c=35,
            power_consumption=12, soc=85)),
        (2, "Ignition OFF |  Gear N + Parking Brake        |  parked, 0.0 kW", dict(
            ignition=False,
            flags=encode_flags(parking_brake=True, gear="N"),
            motor_rpm=0, battery_power_kw=0.0, battery_hv_v=400.0,
            remaining_power=0, odo=15234, battery_temp_c=30,
            power_consumption=12, soc=85)),
        (3, "Ignition ON  |  Gear R + Hazard                |  regen -5.0 kW", dict(
            ignition=True,
            flags=encode_flags(hazard=True, gear="R"),
            motor_rpm=500, battery_power_kw=-5.0, battery_hv_v=398.0,
            remaining_power=20, odo=15240, battery_temp_c=36,
            power_consumption=13, soc=84)),
        (4, "Ignition ON  |  Gear D + Headlight             |  REGEN -30.0 kW", dict(
            ignition=True,
            flags=encode_flags(headlight=True, gear="D"),
            motor_rpm=2500, battery_power_kw=-30.0, battery_hv_v=402.3,
            remaining_power=35, odo=15245, battery_temp_c=34,
            power_consumption=14, soc=86)),
    ]

    for i, (num, label, kwargs) in enumerate(scenarios):
        if args.only is not None and args.only != num:
            continue
        content = build_ev_position_content(timestamp=now, **kwargs)
        frame = build_startek_frame("x", args.imei, "000", content)
        send_frame(args.host, args.port, frame, f"[{num}] {label}")
        if i < len(scenarios) - 1 and args.only is None:
            time.sleep(args.delay)

    print("Done. Open Traccar UI ->device details / API "
          "/api/positions?deviceId=... to verify EV attributes.")


if __name__ == "__main__":
    main()
