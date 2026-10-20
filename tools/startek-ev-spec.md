# เอกสารสรุป: Startek Protocol — EV Data Extension

**เอกสารสำหรับ:** ทีมพัฒนา Hardware (firmware GPS device)
**ผู้ดูแล:** ทีม Backend (MarsX Things)
**Protocol:** Startek TCP  |  **Port:** 5222
**เวอร์ชันเอกสาร:** 1.6

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
| 2 | **(ว่าง)** | — | — | — | — | **ส่งเป็น empty ระหว่าง `\|\|`** | ไม่สร้าง attribute |
| 3 | **Battery HV** | `HV` | **double** | V | 0-1016 | **`V ÷ 4`** (precision 4 V, 1-byte limit) | `raw × 4.0` |
| 4 | **Remaining Range (high)** | `remainingRange` | int | **km** | 0-25,499 | **`km ÷ 100`** (หลักร้อย, 0-254) | (รวมกับ slot 5) |
| 5 | **Remaining Range (low)** | — | — | — | 0-99 | **`km % 100`** (2 หลักท้าย) | **`remainingRange = slot4 × 100 + slot5`** |
| 6 | **Status Flags** | (หลายค่า) | int (bitmask) | — | 0-255 | ดูข้อ 5.1 | ดูข้อ 5.1 |
| 7 | **Battery Temp** | `batteryTemp` | int | °C | -40 ถึง 215 | **`actual + 40`** | `raw − 40` |
| 8 | **Battery Power (±regen)** | `batteryPower` | **double** | kW | **-999.9 ถึง +5553.4** | **`kW × 10 + 10000`** (16-bit, 1-65534, **0 = ไม่มีข้อมูล**) | `(raw − 10000) / 10.0` |
| 9 | **SoC** | `soc` | int | % | 0-100 | ส่งค่าตรงๆ **ตามด้วย `%`** | raw |

**หมายเหตุสำคัญ (V1.6):**
- **Slot 2 ว่างเสมอ (V1.6)** — เดิมเก็บ batteryPower แต่ย้ายไป slot 8 (ซึ่งเป็น 16-bit ให้ความละเอียด 0.1 kW ตรงหน้าปัด) · HW ส่ง empty ระหว่าง `||`
- **Slot 5 เลิกเป็น ODO** — เดิมใช้อัปเดต main odometer แต่ปัจจุบันถูกใช้ส่งเลข 2 หลักท้ายของ `remainingRange` → **HW ห้ามส่งค่า ODO ใน slot 5** (ค่าที่เกิน 99 จะทำให้ `remainingRange` ที่ decode ออกมาเพี้ยน)
- **Slot 8 เปลี่ยนความหมาย (V1.6)** — เดิมเป็น `powerConsumption` (kWh สะสม) · ปัจจุบันเป็น `batteryPower` (kW) · **raw = 0 = ไม่มีข้อมูล** backend ไม่สร้าง attribute · แหล่งข้อมูล: BMS HV×I (ตรงหน้าปัด ตอนชาร์จติดลบถูกต้อง)

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

### 8.1 EV ขับ Drive กำลังชาร์จ + ไฟหน้าเปิด (consume 45.5 kW)

**สถานการณ์:**
- Motor RPM 3000, Battery Power **+45.5 kW** (consume), Battery HV **588 V**
- Remaining Range **328 km**, Battery Temp 35°C
- SoC 85%, Gear = **D**, Charging + Headlight
- Ignition ON → input hex = `02`

**Encoding:**
- slot 2 **ว่าง** (V1.6: batteryPower ย้ายไป slot 8)
- Battery HV raw = `588 ÷ 4` = **147**
- Remaining Range: slot4 = `328 ÷ 100` = **3**, slot5 = `328 % 100` = **28**
- Battery Temp raw = `35 + 40` = **75**
- Battery Power raw = `45.5 × 10 + 10000` = **10455**
- Flags = `64 (gear D) + 2 (headlight) + 1 (charge)` = **67**

**Frame:**
```
&&x173,868825064282040,000,0,,260721120000,A,13.809656,100.558255,10,1.2,0,57,8,925,
418|40|038C|000083CD,31,00000015,02,00,0016|016A|0000|0000,1,,,
3000||147|3|28|67|75|10455|85%FE\r\n
```

**Server จะ decode ได้:**
```json
{
  "rpm": 3000,
  "batteryPower": 45.5,
  "HV": 588.0,
  "remainingRange": 328,
  "batteryTemp": 35,
  "soc": 85,
  "charge": true,
  "headlight": true,
  "gearPositions": "D",
  "ignition": true,
  "poweron": true
}
```

### 8.2 EV กำลัง regen (เบรก) — Battery Power ค่าติดลบ

**สถานการณ์:** ขับ Drive อยู่ กำลังเบรก regen เข้า battery **−20.4 kW**, ไฟหน้าเปิด, ระยะเหลือ 325 km

**Encoding:**
- Battery Power raw = `−20.4 × 10 + 10000` = **9796**
- Remaining Range: slot4 = `3`, slot5 = `25`
- Flags = `64 (gear D) + 2 (headlight)` = **66**

**Frame OBD block:**
```
3000||147|3|25|66|75|9796|84%
```

**Decode:**
```json
{
  "batteryPower": -20.4,    ← ค่าติดลบ = ไฟฟ้าเข้า battery (regen)
  "HV": 588.0,
  "remainingRange": 325,
  "gearPositions": "D",
  "headlight": true,
  ...
}
```

### 8.3 EV จอด + ดึงเบรกมือ + Gear N + ignition off

**สถานการณ์:** Motor ไม่หมุน, Gear N, Parking Brake ดึง, ignition OFF, ระยะเหลือ 30 km · ไฟที่รถใช้เอง +3.4 kW

**Encoding:**
- Battery Power raw = `3.4 × 10 + 10000` = **10034**
- Remaining Range: slot4 = `0`, slot5 = `30`
- Flags = `16 (parking brake)` + `0 (gear N)` = **16**

**Frame OBD block:**
```
0||147|0|30|16|70|10034|85%
```

**Decode:**
```json
{
  "rpm": 0,
  "batteryPower": 3.4,
  "HV": 588.0,
  "remainingRange": 30,
  "gearPositions": "N",
  "parkingBrake": true,
  "ignition": false,
  "poweroff": true
}
```

### 8.4 EV ชาร์จ DC fast charger

**สถานการณ์:** จอดชาร์จ DC, ไฟฟ้าเข้าแบตแรง **−148.3 kW** (ตรงหน้าปัด), ignition OFF, ระยะเหลือ 220 km

**Encoding:**
- Battery Power raw = `−148.3 × 10 + 10000` = **8517**
- Remaining Range: slot4 = `2`, slot5 = `20`
- Flags = `16 (parking brake) + 1 (charge)` + `0 (gear N)` = **17**

**Frame OBD block:**
```
0||150|2|20|17|76|8517|75%
```

**Decode:**
```json
{
  "batteryPower": -148.3,
  "HV": 600.0,
  "remainingRange": 220,
  "gearPositions": "N",
  "parkingBrake": true,
  "charge": true
}
```

### 8.5 รถดับ / บอร์ดเพิ่งเปิด (slot 8 = 0)

**สถานการณ์:** ไม่มีข้อมูลจาก BMS → slot 8 ส่ง **0** → backend **ไม่สร้าง** `batteryPower`

**Frame OBD block:**
```
0||147|0|30|16|75|0|85%
```

**Decode:** (ไม่มี `batteryPower` ใน JSON — attribute ถูกข้าม)

---

## 9. Encoding Rules — สรุปสั้น (Cheat Sheet)

| กฎ | ค่าอย่างไร | ตัวอย่าง |
|---|---|---|
| **จำนวนเต็มบวก** (RPM, SoC) | ส่งค่าตรงๆ | Motor RPM 3000 → ส่ง `3000` |
| **Slot 2 (V1.6)** | **ว่างเสมอ** — ส่ง empty ระหว่าง `\|\|` | เช่น `3000\|\|147\|...` |
| **Battery HV (0-1016 V, 4 V precision)** | **`V ÷ 4`** (slot 3) | 588 V → `147` |
| **Remaining Range (0-25,499 km, 1 km precision)** | **slot 4** = `km ÷ 100`, **slot 5** = `km % 100` | 328 km → slot4=`3`, slot5=`28`; 30 km → slot4=`0`, slot5=`30` |
| **Battery Temp (มีค่าติดลบได้)** | **`actual + 40`** (slot 7) | −20°C → `20`; 80°C → `120` |
| **Battery Power (V1.6: slot 8, -999.9 ถึง +5553.4 kW, 0.1 kW precision)** | **`kW × 10 + 10000`** (16-bit, 1-65534) · **0 = ไม่มีข้อมูล** | +45.5 kW → `10455`; −20.4 kW → `9796`; 0 kW → `10000`; ไม่มีข้อมูล → `0` |
| **SoC** | ต่อท้ายด้วย `%` เสมอ | 85% → ส่ง `85%` |
| **Flags** | Bitmask 8 บิต (0-255) — 6 flag + 2 บิต gear | ดู 5.1 |
| **Gear** | 2 บิต (bits 6-7) → N=0, D=64, R=128 | ห้ามส่ง 192 (reserved) |
| **Field ว่าง (ไม่มีข้อมูล)** | ปล่อยว่างระหว่าง `\|` | เช่น `3000\|\|147\|0\|30\|...` |
| **Delimiter** | `\|` ระหว่าง OBD slots, `,` ระหว่าง field หลัก | อย่าสลับ! |
| **ค่า 255 (= `0xFF`) ใน 1-byte slot** | **ห้ามส่ง** — ถือเป็น "ช่องว่าง" | HV > 1016 V → clamp เป็น 254 |
| **ค่า 65535 (= `0xFFFF`) ใน slot 8** | **ห้ามส่ง** — iStartek ทิ้งทั้งก้อน | kW เกินช่วง → clamp เป็น 65534 |

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

### ❌ ผิด: ส่ง Battery HV เป็นทศนิยม หรือเกิน 254
```
Battery HV 588.8V, ส่ง `588.8`          ← ผิด! slot รับเฉพาะ (d+) ไม่รับจุด
Battery HV 588.8V, ส่ง `5888`           ← ผิด! iStartek บีบ slot 3 เหลือ 1 byte (0-254) ค่าเกิน 254 จะถูกตัด
```
### ✅ ถูก: หาร 4 ก่อนส่ง (precision 4 V)
```
Battery HV 588.8V → ส่ง `147` (server จะคูณ 4 → 588)
Battery HV 400V → ส่ง `100` (server → 400)
```

### ❌ ผิด: ส่ง Battery Power ที่ slot 2 (V1.6: ย้ายไป slot 8 แล้ว)
```
slot 2 = 145 (ตามสูตร V1.5)                   ← ผิด! V1.6 slot 2 ต้องว่าง
```
### ✅ ถูก: slot 2 ว่าง · ส่ง batteryPower ที่ slot 8 ด้วยสูตร `kW × 10 + 10000`
```
Battery Power = -20.4 kW → slot 8 = `-20.4 × 10 + 10000` = `9796`
Battery Power = +45.5 kW → slot 8 = `45.5 × 10 + 10000` = `10455`
Battery Power = 0 kW → slot 8 = `10000` (ไม่ใช่ `0`!)
ไม่มีข้อมูล → slot 8 = `0` (backend ไม่สร้าง attribute)
```
*หมายเหตุ: ค่าที่คูณ 10 เพื่อให้ได้ความละเอียด 0.1 kW ตรงหน้าปัดรถ · ค่าต้องอยู่ใน 1..65534 (0 = ไม่มีข้อมูล, 65535 = iStartek ทิ้งทั้งก้อน)*

### ❌ ผิด: ส่ง Remaining Range ตรงๆ ใน slot 4
```
Remaining Range = 328 km, ส่ง slot4=`328`       ← ผิด! slot รับเฉพาะ 0-254
```
### ✅ ถูก: แยกเป็น 2 slot (V1.5)
```
Remaining Range = 328 km → slot4 = 328÷100 = `3`, slot5 = 328%100 = `28`
Remaining Range = 30 km  → slot4 = `0`,          slot5 = `30`
Remaining Range = 1000 km → slot4 = `10`,         slot5 = `0`
```
*หมายเหตุ: slot 5 เลิกเป็น ODO แล้ว — ห้ามส่งค่า odometer ใน slot 5*

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

Simulator นี้ส่ง scenario ทดสอบ (V2.8 encoding):
1. Parked + Parking Brake, +3.4 kW idle (ไฟที่รถใช้เอง), 30 km
2. Driving long-range + Headlight, **+75.7 kW**, **328 km** (ทดสอบ slot4+slot5 split)
3. Regen braking, **-20.4 kW**
4. Peak discharge + Hazard, +150.0 kW
5. **DC fast charging, -148.3 kW** (ตรงหน้าปัด)

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
| 1.4 | 2026-10-08 | **Slot 3 (HV): สูตรเปลี่ยนจาก `raw / 10` → `raw × 4`** เพราะ iStartek บีบ slot เหลือ 1 byte (0-254) ค่า 5900+ จาก `V × 10` ส่งไม่ได้ (precision ลดจาก 0.1 V เหลือ 4 V, range เพิ่มเป็น 0-1016 V). **Slot 4: rename `remainingPower` (kW) → `remainingRange` (km)** เพื่อสะท้อนค่าจริงที่รถ Dongfeng EV ส่งมา (ระยะวิ่งที่เหลือจากหน้าปัด). Slot 2 (batteryPower) และ Slot 5 (odometer) firmware ไม่ส่งอีก (ติด 1-byte limit) decoder เดิมมี null-check อยู่แล้ว ไม่ต้องแก้ |
| 1.5 | 2026-10-12 | **Slot 2 (batteryPower): สูตรเปลี่ยนจาก `(kW × 10) + 1000` → `kW + 100`** (offset +100, precision 1 kW, range -100..+154, clamp 0..254) — เพราะ 1-byte limit เดิมใส่ไม่ได้. **Slot 4+5 (remainingRange): เปลี่ยนเป็น split encoding** — slot 4 = `km ÷ 100`, slot 5 = `km % 100` (1 km precision, range 0-25,499). **Slot 5 เลิกเป็น ODO ถาวร** — decoder ไม่เขียน `KEY_OBD_ODOMETER` จาก slot 5 อีก (เพื่อไม่ให้ล้าง odometer จริงของรถ). ⚠️ Deploy order: **backend ก่อน แล้วค่อยแฟลช firmware** ไม่งั้น odometer หลักของรถจะถูกเขียนทับเป็น 0-99 km |
| **1.6** | **2026-10-14** | **Slot 2 ว่างเสมอ** · **batteryPower ย้ายไป slot 8** (เดิม `powerConsumption`) ด้วยสูตร **`kW × 10 + 10000`** (16-bit, range −999.9..+5553.4 kW, precision **0.1 kW** ตรงหน้าปัด) · **slot 8 raw = 0 = ไม่มีข้อมูล** backend ไม่สร้าง attribute · **`powerConsumption` เลิกใช้ถาวร** · แหล่งข้อมูล batteryPower = BMS frame `0x0CFFEAF4` (HV×I) ตรงหน้าปัดทั้งขับและชาร์จ · ไม่มีความเสี่ยงเขียนทับข้อมูล (ไม่ต้อง deploy order เข้ม) |

---

## 13. ติดต่อ

- **Backend team:** ตั้ง issue ใน Git repo หรือ ping ในช่อง team chat
- **เอกสารนี้อยู่ที่:** `tools/startek-ev-spec.md` ใน repo Traccar
