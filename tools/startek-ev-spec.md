# เอกสารสรุป: Startek Protocol — EV Data Extension

**เอกสารสำหรับ:** ทีมพัฒนา Hardware (firmware GPS device)
**ผู้ดูแล:** ทีม Backend (MarsX Things)
**Protocol:** Startek TCP  |  **Port:** 5222
**เวอร์ชันเอกสาร:** 1.3

---

## 1. Overview

Traccar server ได้รับการปรับปรุงให้รองรับรถ EV โดยใช้ **frame format เดิมของ Startek ทุกประการ** แต่จะแปลความหมายของ **9 slot ใน OBD extended block** ให้เป็นค่าเฉพาะของรถ EV เมื่อ device ถูกตั้ง category เป็น `"ev"` (หรืออื่นๆ ที่ขึ้นต้นด้วย `ev`)

**เป้าหมาย:** Hardware ส่ง frame ในโครงสร้างเดียวกันเสมอ ไม่ต้องมี logic แยก ICE / EV — server เป็นคนตัดสินเองจาก device category

---

## 2. Trigger: เมื่อไหร่ที่ Server จะใช้ EV Mode

Server จะแปล frame เป็น EV **เมื่อและเมื่อเท่านั้น** ที่ device ตัวนั้นบน Traccar UI มีฟิลด์ **Category** ขึ้นต้นด้วย `ev` (case-insensitive)

**ค่าที่ยอมรับ:**
- `ev`
- `evcar`
- `evtruck`
- `evbus`
- `EV`, `EVCAR` (ก็ใช้ได้ — case-insensitive)

**ค่าที่ไม่ trigger EV mode:**
- `car`, `truck`, `default`, empty, ค่าอื่นที่ไม่ขึ้นต้นด้วย `ev`

→ **Hardware ไม่มีบทบาทตัดสิน ICE / EV** — เป็นหน้าที่ผู้ตั้งค่า device บน Traccar UI

---

## 3. Frame Format (ไม่เปลี่ยนจากเดิม)

```
&&<idx><length>,<imei>,<type>,<content><checksum>\r\n
```

| ส่วน | ตัวอย่าง | หมายเหตุ |
|---|---|---|
| Start marker | `&&` | คงที่ 2 ไบต์ |
| Index | `x` | 1 อักษร (message counter/flag) |
| Length | `173` | ความยาว body (จำนวน byte หลัง `,` แรก) - 3 |
| IMEI | `868825064282040` | 15 หลัก |
| Type | `000` | 3 ตัวอักษร; `000` = position |
| Content | (ดูข้อ 4) | payload จริง |
| Checksum | `FE` | 2 hex chars (server ไม่ verify แต่ต้องมีและเป็น hex) |
| Terminator | `\r\n` | คงที่ |

---

## 4. Content Structure สำหรับ Type `000` (Position)

Content ประกอบด้วย field ต่อไปนี้ **คั่นด้วย comma** (`,`) และ **สำหรับ OBD extended block คั่นด้วย pipe** (`|`):

```
event,eventData,datetime,valid,lat,lon,sats,hdop,speed,course,alt,mainOdo,
mcc|mnc|lac|cid,rssi,status,input,output,power|batt|adc|adc,
extCount,fuel,temp,<OBD_9_SLOTS>
```

### Field ที่เกี่ยวข้องกับ EV โดยเฉพาะ

| Field | หมายเหตุสำหรับ EV |
|---|---|
| `input` (hex) | **บิต 1** = 1 → ignition ON → server จะยิง `alarm: powerOn` เพิ่มเข้ามาให้อัตโนมัติ (ดูข้อ 6) |
| `OBD_9_SLOTS` | ดูตารางแมป EV ด้านล่าง |

---

## 5. **OBD 9-Slot Mapping** — หัวใจของ EV Support

โครงสร้าง: **9 field คั่นด้วย pipe** (`|`) โดย field ที่ 9 ต้องลงท้ายด้วย `%` (สำหรับ SoC):

```
<slot1>|<slot2>|<slot3>|<slot4>|<slot5>|<slot6>|<slot7>|<slot8>|<slot9>%
```

### ตารางแมปแบบละเอียด (Hardware ต้องทำตามนี้)

| # | ชื่อ Field | Attribute key | ประเภท | หน่วย | Range | Encoding (Hardware → raw ที่ส่ง) | Server แปลง |
|:-:|---|---|:-:|:-:|---|---|---|
| 1 | **Motor RPM** | `rpm` | int | rpm | 0-65535 | ส่งค่าตรงๆ | raw |
| 2 | **Battery Power (±regen)** | `batteryPower` | **double** | kW | **±100.0** | **`(kW × 10) + 1000`** (bias +1000, signed) | `(raw − 1000) / 10.0` |
| 3 | **Battery HV** | `HV` | **double** | V | 0-1000.0 | **`V × 10`** (precision 0.1) | `raw / 10.0` |
| 4 | **Remaining Power** | `remainingPower` | int | kW | 0-1000 | ส่งค่าตรงๆ | raw |
| 5 | **ODO** | `odometer` | long | km | 0-2 พันล้าน | ส่งค่าตรงๆ | **ส่ง 0 = ไม่ update** main odometer |
| 6 | **Status Flags** | (หลายค่า) | int (bitmask) | — | 0-255 | ดูข้อ 5.1 | ดูข้อ 5.1 |
| 7 | **Battery Temp** | `batteryTemp` | int | °C | -40 ถึง 215 | **`actual + 40`** | `raw − 40` |
| 8 | **Trip Consumption** | `powerConsumption` | int | kWh | 0-2 พันล้าน | ส่งค่าตรงๆ (cumulative) | raw |
| 9 | **SoC** | `soc` | int | % | 0-100 | ส่งค่าตรงๆ **ตามด้วย `%`** | raw |

### 5.1 **Slot 6 (Status Flags) — Bitmask Detail** ⭐

`Status Flags` = ตัวเลข decimal 1 ค่า (0-255) โดยแบ่งเป็น 2 กลุ่ม:
- **บิต 0-5** = 6 flag แบบ boolean (on/off)
- **บิต 6-7** = 2 บิตเข้ารหัส gear position (N/D/R)

```
บิต:    7    6   |   5      4        3         2         1         0
        │    │   |   │      │        │         │         │         │
        └Gear┘   |  Hazard  Park    TurnR    TurnL    Headlight  Charging
       (2 bits)             Brake
```

#### 5.1.1 Boolean Flags (บิต 0-5)

| บิต | Flag | Attribute key | ค่าเมื่อ = 1 | ความหมาย |
|:-:|---|---|:-:|---|
| 0 | Charging | `charge` | **1** | 1 = กำลังชาร์จ, 0 = ไม่ชาร์จ |
| 1 | Headlight | `headlight` | **2** | 1 = ไฟหน้าเปิด, 0 = ปิด |
| 2 | Turn Left | `turnLeft` | **4** | 1 = ไฟเลี้ยวซ้ายเปิด, 0 = ปิด |
| 3 | Turn Right | `turnRight` | **8** | 1 = ไฟเลี้ยวขวาเปิด, 0 = ปิด |
| 4 | **Parking Brake** | `parkingBrake` | **16** | 1 = ดึงเบรกมือ, 0 = ปลด |
| 5 | Hazard | `hazard` | **32** | 1 = ไฟฉุกเฉินเปิด, 0 = ปิด |

**พฤติกรรมของ Server (Boolean flags):**
- Server สร้าง attribute **เฉพาะบิตที่ = 1** เท่านั้น — บิตที่ = 0 จะไม่มี attribute
- เช่น Flags=3 → มี `charge: true`, `headlight: true` เท่านั้น (ไม่มี `turnLeft: false` ฯลฯ)

#### 5.1.2 Gear Position (บิต 6-7) ⭐

| บิต 7 | บิต 6 | ค่ารวมของ 2 บิต | ค่าที่บวกเข้า Flags | Gear | Attribute `gearPositions` |
|:-:|:-:|:-:|:-:|:-:|:-:|
| 0 | 0 | 00 | **0** | **N** (Neutral) | `"N"` |
| 0 | 1 | 01 | **64** | **D** (Drive) | `"D"` |
| 1 | 0 | 10 | **128** | **R** (Reverse) | `"R"` |
| 1 | 1 | 11 | 192 | reserved | **ไม่ set attribute** |

*หมายเหตุ: ไม่มี Park (P) — ใช้ Parking Brake (bit 4) แทน*

#### 5.1.3 สูตรคำนวณ Flags (รวมทุกบิต)

```
Flags = 128×gear_R + 64×gear_D + 32×Hazard + 16×ParkBrake
      + 8×TurnR + 4×TurnL + 2×Headlight + 1×Charging
```

**หมายเหตุ:** gear ต้องเลือกทีละค่า — ห้ามเปิดทั้ง 64 และ 128 พร้อมกัน (จะเป็น 192 = reserved)

#### 5.1.4 ตัวอย่างค่า Flags

| สถานการณ์ | คำนวณ | Flags | Binary |
|---|---|:-:|:-:|
| Gear N, ไม่มีอะไร active | 0 | **0** | `00000000` |
| Gear N, กำลังชาร์จ | 1 | **1** | `00000001` |
| Gear D, ไฟหน้า | 64 + 2 | **66** | `01000010` |
| Gear D, ชาร์จ + ไฟหน้า | 64 + 2 + 1 | **67** | `01000011` |
| Gear R, Parking Brake, Hazard | 128 + 16 + 32 | **176** | `10110000` |
| Gear D, ไฟหน้า + ไฟเลี้ยวซ้าย + Hazard | 64 + 2 + 4 + 32 | **102** | `01100110` |

---

## 6. Ignition & Power Attribute (EV Only)

Server จะใช้ **บิต 1 ของฟิลด์ `input`** (ที่เป็น hex อยู่ก่อน `output`) เป็นสถานะ ignition และสร้าง attribute เพิ่มเติมอัตโนมัติ:

| `input` (hex) | บิต 1 | `ignition` | Attribute ที่ server สร้างเพิ่ม |
|:-:|:-:|:-:|:-:|
| `00` | 0 | `false` | `poweroff: true` |
| `02` | 1 | `true` | `poweron: true` |
| `03` | 1 | `true` | `poweron: true` |
| ... | ... | ... | ... |

**Hardware ต้องรับผิดชอบ:** ส่งค่า `input` ให้บิต 1 สะท้อนสถานะ ignition ของรถ EV ให้ถูกต้อง

---

## 7. Event-Triggered Reporting (HW Wiring) ⚡

โดย default iStartek จะส่ง position frame ตามรอบเวลา (periodic reporting) ซึ่งอาจล่าช้าเป็นสิบวินาที
สำหรับ event สำคัญบางอย่าง **ต้องส่ง frame ทันที** เพื่อไม่ให้พลาดเหตุการณ์ — ทำได้โดยใช้ **hardware I/O trigger**

### 7.1 หลักการทำงาน

MCU ของ HW ต้อง output **สัญญาณไฟ Active-HIGH (+5 V)** ไปเข้า input port **INT3** ของ iStartek
เมื่อ iStartek เห็น INT3 = HIGH → **trigger ให้ส่ง position frame ทันที** (พร้อมข้อมูล OBD block ตามที่ spec นี้กำหนด)

```
  [MCU output pin] ────── +5V (Active HIGH) ────── [iStartek INT3 input]
        ↑                                                  │
   ตรวจจับ event                                           ▼
                                                    ส่ง frame ทันที
```

### 7.2 Event ที่ต้อง trigger INT3

Trigger ทุกครั้งที่มี **การเปลี่ยนสถานะ (edge)** ต่อไปนี้:

| Event | Trigger เมื่อ |
|---|---|
| **Charging** | เปลี่ยนจาก (ทั้ง ON → OFF และ OFF → ON) |
| **Headlight** | เปลี่ยนจาก OFF → ON |
| **Turn Left** | เปลี่ยนจาก OFF → ON |
| **Turn Right** | เปลี่ยนจาก OFF → ON |
| **Parking Brake** | เปลี่ยนสถานะ (ทั้ง ON → OFF และ OFF → ON) |
| **Hazard** | เปลี่ยนจาก OFF → ON |
| **Gear position** | เปลี่ยนตำแหน่ง (N ↔ D, D ↔ R, N ↔ R ทุกทิศทาง) |

**หมายเหตุ:**
- **Ignition ON/OFF** iStartek มี logic แจ้งเอง ไม่ต้อง trigger เพิ่ม

### 7.3 พฤติกรรมสัญญาณ

- **Level:** Active-HIGH (+5 V DC)
- **Pulse width แนะนำ:** อย่างน้อย **200 ms** เพื่อให้ iStartek sample เจอ
- **หลัง trigger:** ปล่อยกลับ LOW (0 V) — อย่าค้าง HIGH ตลอด ไม่งั้นจะไม่ trigger ครั้งถัดไป
- **ถ้ามี event หลายอย่างเกิดพร้อมกัน** (เช่น เปลี่ยน gear + เปิดไฟเลี้ยว): ยิง pulse **ครั้งเดียวก็พอ** (frame เดียวจะพา flags ทั้งหมดไปด้วย)

### 7.4 ตัวอย่าง Timing Diagram

```
เหตุการณ์:    ---เปิดไฟหน้า---เปลี่ยน gear D→R---ดึงเบรกมือ---
                    │              │              │
INT3 signal:  ______┴───┴__________┴───┴__________┴───┴______
                    ↑              ↑              ↑
              iStartek       iStartek       iStartek
              ส่ง frame       ส่ง frame       ส่ง frame
              (ทันที)        (ทันที)         (ทันที)
```

### 7.5 Alarm Code ที่ iStartek จะส่งเมื่อ INT3 trigger

ตาม iStartek official docs (Appendix A — Alarm Event Codes):

| alm-code | Description | Server แปลเป็น |
|:-:|---|---|
| **5** | Input3 active (rising edge → INT3 = HIGH) | `alarm: "driverBehavior"` |
| **6** | Input3 inactive (falling edge → INT3 = LOW) | `alarm: "driverBehavior"` |

**หมายเหตุ:**
- เดิม Traccar map code 5, 6 เป็น `alarm: "door"` — เปลี่ยนเป็น `"driverBehavior"` เพื่อให้ตรงกับความหมายจริง (ไม่ใช่ประตูรถ แต่คือ driver-controlled state changes)
- Server จะเก็บ attribute `alarm: "driverBehavior"` ในทุก position frame ที่มาจาก INT3 trigger
- ทีม Backend สามารถตั้ง **Notification rule** ใน Traccar เพื่อ log / แจ้งเตือน alarm นี้แยกได้

### 7.6 ทำไมต้องทำแบบนี้

- **Server มองไม่เห็นการเปลี่ยนแปลง** ระหว่าง periodic frame — ถ้าคนขับเปิด/ปิด ไฟเลี้ยวเร็วๆ ระหว่างช่วงส่ง จะไม่ได้บันทึกเลย
- **การ trigger ทำให้ event ทุกครั้งถูก log** — สำคัญมากสำหรับ compliance / driver behavior analysis
- **ไม่ต้องเพิ่ม field ใหม่ใน protocol** — ใช้กลไก INT3 ของ iStartek ที่มีอยู่แล้ว

### 7.7 การตั้งค่า iStartek

- **iStartek ต้องเปิดใช้ INT3 mode** = "send report on trigger" (ดู manual iStartek)
- Setting นี้ทำครั้งเดียวตอน commission device
- ทีม Backend สามารถส่งคำสั่ง configure ผ่าน Traccar UI ได้หลัง connect

---

## 8. Full Payload Examples

### 8.1 EV ขับ Drive กำลังชาร์จ + ไฟหน้าเปิด (consume 50 kW)

**สถานการณ์:**
- Motor RPM 3000, Battery Power **+50.0 kW** (consume), Battery HV **400.0 V**
- Remaining 30 kW, ODO 15234 km, Battery Temp 35°C
- Trip Consumption 12 kWh, SoC 85%
- Gear = **D**, Charging + Headlight
- Ignition ON → input hex = `02`

**Encoding:**
- Battery Power raw = `(50 × 10) + 1000` = **1500**
- Battery HV raw = `400.0 × 10` = **4000**
- Battery Temp raw = `35 + 40` = **75**
- Flags = `64 (gear D) + 2 (headlight) + 1 (charge)` = **67**

**Frame:**
```
&&x173,868825064282040,000,0,,260721120000,A,13.809656,100.558255,10,1.2,0,57,8,925,
418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,
3000|1500|4000|30|15234|67|75|12|85%FE\r\n
```

**Server จะ decode ได้:**
```json
{
  "rpm": 3000,
  "batteryPower": 50.0,
  "HV": 400.0,
  "remainingPower": 30,
  "odometer": 15234,
  "batteryTemp": 35,
  "powerConsumption": 12,
  "soc": 85,
  "charge": true,
  "headlight": true,
  "gearPositions": "D",
  "ignition": true,
  "poweron": true
}
```

### 8.2 EV กำลัง regen (เบรก) — Battery Power ค่าติดลบ

**สถานการณ์:** ขับ Drive อยู่ กำลังเบรก regen เข้า battery **−30.0 kW**, ไฟหน้าเปิด

**Encoding:**
- Battery Power raw = `(−30 × 10) + 1000` = **700**
- Flags = `64 (gear D) + 2 (headlight)` = **66**

**Frame OBD block:**
```
3000|700|4000|30|15240|66|75|12|84%
```

**Decode:**
```json
{
  "batteryPower": -30.0,    ← ค่าติดลบ = ไฟฟ้าเข้า battery (regen)
  "HV": 400.0,
  "gearPositions": "D",
  "headlight": true,
  ...
}
```

### 8.3 EV จอด + ดึงเบรกมือ + Gear N + ignition off

**สถานการณ์:** Motor ไม่หมุน, Gear N, Parking Brake ดึง, ignition OFF

**Encoding:**
- Battery Power raw = `(0 × 10) + 1000` = **1000** (Battery ไม่มีการใช้/ชาร์จ)
- Flags = `16 (parking brake)` + `0 (gear N)` = **16**

**Frame OBD block:**
```
0|1000|4000|0|15234|16|70|12|85%
```

**Decode:**
```json
{
  "rpm": 0,
  "batteryPower": 0.0,
  "HV": 400.0,
  "gearPositions": "N",
  "parkingBrake": true,
  "ignition": false,
  "poweroff": true
}
```

### 8.4 EV ถอย + Hazard

**สถานการณ์:** ถอยเข้าจอด, เปิด hazard, ignition ON

**Encoding:**
- Battery Power raw = `(−5 × 10) + 1000` = **950** (regen เล็กน้อยตอนชะลอ)
- Flags = `128 (gear R) + 32 (hazard)` = **160**

**Frame OBD block:**
```
500|950|3980|20|15240|160|76|13|84%
```

**Decode:**
```json
{
  "batteryPower": -5.0,
  "HV": 398.0,
  "gearPositions": "R",
  "hazard": true
}
```

---

## 9. Encoding Rules — สรุปสั้น (Cheat Sheet)

| กฎ | ค่าอย่างไร | ตัวอย่าง |
|---|---|---|
| **จำนวนเต็มบวก** (RPM, remainingPower, powerConsumption, SoC) | ส่งค่าตรงๆ | Motor RPM 3000 → ส่ง `3000` |
| **Battery Power (±100 kW, 0.1 precision)** | **`(kW × 10) + 1000`** (bias) | +50 kW → `1500`; −30 kW → `700`; 0 kW → `1000` |
| **Battery HV (0-1000 V, 0.1 precision)** | **`V × 10`** | 400.5 V → `4005` |
| **Battery Temp (มีค่าติดลบได้)** | **`actual + 40`** | −20°C → `20`; 80°C → `120` |
| **SoC** | ต่อท้ายด้วย `%` เสมอ | 85% → ส่ง `85%` |
| **ODO = 0** | ไม่ update main odometer | ส่ง `0` เมื่อไม่รู้ค่า |
| **Flags** | Bitmask 8 บิต (0-255) — 6 flag + 2 บิต gear | ดู 5.1 |
| **Gear** | 2 บิต (bits 6-7) → N=0, D=64, R=128 | ห้ามส่ง 192 (reserved) |
| **Field ว่าง (ไม่มีข้อมูล)** | ปล่อยว่างระหว่าง `\|` | เช่น `3000\|\|4000\|...` = ไม่มี Battery Power |
| **Delimiter** | `\|` ระหว่าง OBD slots, `,` ระหว่าง field หลัก | อย่าสลับ! |

---

## 10. Common Pitfalls (ที่ HW ต้องระวัง)

### ❌ ผิด: ลืม `%` ท้าย SoC
```
...|12|85FE\r\n         ← ผิด! server จะไม่รู้ว่าตรงไหนคือ SoC
```
### ✅ ถูก:
```
...|12|85%FE\r\n
```

### ❌ ผิด: ส่ง Battery Temp ค่าดิบ (ไม่ + 40)
```
Battery Temp = -20°C, ส่ง `-20`         ← ผิด! slot รับเฉพาะ (d+) = เลขบวก, regex ไม่ match ทั้ง frame จะพัง
```
### ✅ ถูก:
```
Battery Temp = -20°C, ส่ง `20` (คือ -20+40)
```

### ❌ ผิด: ส่ง Flags เป็น binary string
```
...|1|10011|75|...     ← ผิด! server อ่านเป็น decimal 10011
```
### ✅ ถูก: แปลง binary เป็น decimal ก่อนส่ง
```
Binary 010011 → Decimal 19 → ส่ง `19`
```

### ❌ ผิด: ส่ง Battery HV เป็นทศนิยม
```
Battery HV 400.5V, ส่ง `400.5`          ← ผิด! slot รับเฉพาะ (d+) ไม่รับจุด
```
### ✅ ถูก: คูณ 10 ก่อนส่ง
```
Battery HV 400.5V → ส่ง `4005` (server จะหารด้วย 10 → 400.5)
```

### ❌ ผิด: ส่ง Battery Power ค่าติดลบตรงๆ
```
Battery Power = -30 kW (regen), ส่ง `-30`      ← ผิด! slot รับเฉพาะเลขบวก
```
### ✅ ถูก: ใช้ bias +1000
```
Battery Power = -30 kW → ส่ง `(-30 × 10) + 1000` = `700`
Battery Power = +50 kW → ส่ง `(50 × 10) + 1000` = `1500`
Battery Power = 0 kW → ส่ง `1000` (ไม่ใช่ `0`!)
```

### ❌ ผิด: ส่ง Gear เกินขอบเขต (bits 6-7 = 11)
```
Flags = 192 (= 128 + 64)               ← ผิด! เปิดทั้งบิต gear R และ gear D → reserved code
```
### ✅ ถูก: เลือก gear ทีละค่าเดียว
```
Gear D → บวก 64 เข้า flags
Gear R → บวก 128 เข้า flags
Gear N → ไม่บวกอะไร (0)
```

---

## 11. Verification / Testing

### Simulator สำหรับทดสอบ (backend ทีมมี)
```
python tools/ev-simulator.py --imei <IMEI> --host <server>
```

Simulator นี้ส่ง scenario ทดสอบ:
1. Drive gear + Charging + Headlight, ignition ON, Battery Power +50.0 kW, SoC 85%
2. Neutral + Parking Brake, ignition OFF, ทุก flag ปิด, Battery Power 0.0 kW
3. Reverse gear + Hazard, regen -5.0 kW, ignition ON

### วิธียืนยันว่า HW ส่งถูก (หลัง deploy)
1. เปิด Traccar Web → device นั้น → View position details
2. ต้องเห็น attribute EV ตามที่ document นี้ระบุ
3. ตรวจ `poweron` / `poweroff` (boolean) ตามสถานะ ignition
4. ตรวจ Flags ที่เปิด — ต้องเห็น attribute แต่ละ flag ที่ = 1 เท่านั้น (ไม่เห็นตัวที่ = 0)
5. ตรวจ `gearPositions` — ต้องเป็น string "N", "D", หรือ "R"
6. ตรวจ `batteryPower` — ค่าติดลบ = regen, ค่าบวก = consume

---

## 12. Change History

| Version | Date | Change |
|:-:|---|---|
| 1.0 | 2026-07-19 | Initial spec — 9-slot mapping + Flags bitmask + Ignition alarm |
| 1.1 | 2026-07-21 | Slot 2 เปลี่ยนจาก Motor Power → **Battery Power ±100 kW** (bias +1000, ×10). Slot 3 (Battery HV) เพิ่ม precision 0.1 (×10). Slot 6 flags: บิต 4 เปลี่ยน Reverse → **Parking Brake**, เพิ่ม **บิต 6-7 = Gear (N/D/R)**. Attribute keys: `HV`, `soc`, `powerConsumption`, `poweron`/`poweroff` |
| 1.2 | 2026-07-21 | เพิ่ม **Section 7 — Event-Triggered Reporting via INT3**: HW ต้อง output +5 V trigger เข้า INT3 เมื่อมี event Headlight ON, Turn L/R ON, Parking Brake เปลี่ยนสถานะ, และ Gear เปลี่ยนตำแหน่ง |
| 1.3 | 2026-07-21 | เพิ่ม `Position.ALARM_DRIVER_BEHAVIOR = "driverBehavior"` ใน model และเปลี่ยน alarm code 5/6 (Input3 active/inactive) จากเดิม `alarm: "door"` → `alarm: "driverBehavior"` เพื่อให้ตรงความหมายจริง (Section 7.5) |

---

## 13. ติดต่อ

- **Backend team:** ตั้ง issue ใน Git repo หรือ ping ในช่อง team chat
- **เอกสารนี้อยู่ที่:** `tools/startek-ev-spec.md` ใน repo Traccar
