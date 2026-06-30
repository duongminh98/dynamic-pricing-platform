#!/usr/bin/env python3
"""Generate an Excalidraw diagram of the Dynamic Pricing Platform architecture."""
import json, random

random.seed(42)

elements = []

def nonce():
    return random.randint(1, 2**31)

def rect(rid, x, y, w, h, bg, stroke="#1e1e1e", label=None, fontsize=16,
         dash=False, font=2, fill="solid"):
    elements.append({
        "id": rid, "type": "rectangle", "x": x, "y": y, "width": w, "height": h,
        "angle": 0, "strokeColor": stroke, "backgroundColor": bg,
        "fillStyle": fill, "strokeWidth": 2,
        "strokeStyle": "dashed" if dash else "solid", "roughness": 1,
        "opacity": 100, "groupIds": [], "frameId": None,
        "roundness": {"type": 3}, "seed": nonce(), "version": 1,
        "versionNonce": nonce(), "isDeleted": False,
        "boundElements": [{"type": "text", "id": rid + "_t"}] if label else [],
        "updated": 1, "link": None, "locked": False,
    })
    if label:
        elements.append({
            "id": rid + "_t", "type": "text", "x": x + 6, "y": y + h/2 - fontsize/2,
            "width": w - 12, "height": fontsize + 4, "angle": 0,
            "strokeColor": stroke, "backgroundColor": "transparent",
            "fillStyle": "solid", "strokeWidth": 2, "strokeStyle": "solid",
            "roughness": 1, "opacity": 100, "groupIds": [], "frameId": None,
            "roundness": None, "seed": nonce(), "version": 1,
            "versionNonce": nonce(), "isDeleted": False, "boundElements": [],
            "updated": 1, "link": None, "locked": False, "text": label,
            "fontSize": fontsize, "fontFamily": font, "textAlign": "center",
            "verticalAlign": "middle", "baseline": fontsize, "containerId": rid,
            "originalText": label, "lineHeight": 1.25,
        })

def label(x, y, text, size=14, color="#1e1e1e", w=200, align="left", font=2):
    elements.append({
        "id": "lbl" + str(nonce()), "type": "text", "x": x, "y": y,
        "width": w, "height": size + 4, "angle": 0, "strokeColor": color,
        "backgroundColor": "transparent", "fillStyle": "solid", "strokeWidth": 2,
        "strokeStyle": "solid", "roughness": 1, "opacity": 100, "groupIds": [],
        "frameId": None, "roundness": None, "seed": nonce(), "version": 1,
        "versionNonce": nonce(), "isDeleted": False, "boundElements": [],
        "updated": 1, "link": None, "locked": False, "text": text,
        "fontSize": size, "fontFamily": font, "textAlign": align,
        "verticalAlign": "top", "baseline": size, "containerId": None,
        "originalText": text, "lineHeight": 1.25,
    })

def arrow(x1, y1, x2, y2, color="#1e1e1e", dash=False, two=False):
    elements.append({
        "id": "arr" + str(nonce()), "type": "arrow", "x": x1, "y": y1,
        "width": abs(x2 - x1), "height": abs(y2 - y1), "angle": 0,
        "strokeColor": color, "backgroundColor": "transparent",
        "fillStyle": "solid", "strokeWidth": 2,
        "strokeStyle": "dashed" if dash else "solid", "roughness": 1,
        "opacity": 100, "groupIds": [], "frameId": None,
        "roundness": {"type": 2}, "seed": nonce(), "version": 1,
        "versionNonce": nonce(), "isDeleted": False, "boundElements": [],
        "updated": 1, "link": None, "locked": False,
        "points": [[0, 0], [x2 - x1, y2 - y1]], "lastCommittedPoint": None,
        "startBinding": None, "endBinding": None,
        "startArrowhead": "arrow" if two else None, "endArrowhead": "arrow",
    })

# Palette
BLUE="#a5d8ff"; VIOLET="#d0bfff"; GREEN="#b2f2bb"; YELLOW="#ffec99"
GRAY="#e9ecef"; ORANGE="#ffd8a8"; PINK="#fcc2d7"

# --- Title ---
label(60, 10, "Dynamic Pricing Platform — Architecture", 28, "#1e1e1e", 900, font=3)

# --- Frontend ---
rect("frontend", 580, 80, 240, 80, BLUE, label="Frontend\n(React + Vite + TS)", fontsize=16)

# --- Gateway / Keycloak ---
rect("kong", 580, 230, 240, 80, VIOLET, label="Kong Gateway\n(port 8000, JWT)", fontsize=16)
rect("keycloak", 900, 230, 200, 80, VIOLET, label="Keycloak\nRS256 / roles", fontsize=15)

# --- Java services row ---
svc = [("customer","customer"),("product","product"),("order","order"),
       ("billing","billing"),("claims","claims"),("notif","notification")]
sx, sw, gap, sy, sh = 60, 170, 24, 420, 80
xs = {}
for i,(k,name) in enumerate(svc):
    x = sx + i*(sw+gap)
    xs[k] = x
    rect(k, x, sy, sw, sh, GREEN, label=name+"\n-service", fontsize=15)

# --- Pricing service ---
px = sx + 6*(sw+gap)
rect("pricing", px, sy, 200, 100, YELLOW,
     label="pricing-service\nFastAPI · LightGBM\nSHAP · 36 models", fontsize=14)
xs["pricing"] = px

# --- Postgres DBs ---
dby, dbh = 580, 56
for i,(k,name) in enumerate(svc):
    x = sx + i*(sw+gap)
    rect("db"+k, x+10, dby, sw-20, dbh, GRAY, label="PG\n"+name, fontsize=12)
rect("dbpricing", px+10, dby, 180, dbh, GRAY, label="PG pricing", fontsize=13)

# --- RabbitMQ bus ---
rect("rabbit", 360, 720, 560, 70, ORANGE,
     label="RabbitMQ  —  exchange: platform.events  (transactional outbox)", fontsize=15)

# --- Observability ---
rect("obs", 1160, 230, 200, 80, PINK, label="Prometheus\n+ Grafana", fontsize=15)

# --- Arrows: sync top-down ---
arrow(700, 160, 700, 230)                       # frontend -> kong
arrow(820, 270, 900, 270, two=True)             # kong <-> keycloak
# kong -> services fan
for k in ["customer","product","order","billing","claims","notif"]:
    arrow(700, 310, xs[k]+sw/2, sy)
arrow(700, 310, px+100, sy)                      # kong -> pricing
# services -> db
for k,_ in svc:
    arrow(xs[k]+sw/2, sy+sh, xs[k]+sw/2, dby)
arrow(px+100, sy+100, px+100, dby)               # pricing -> db

# --- Async: services -> rabbitmq (dashed orange) ---
ORG="#e8590c"
for k in ["order","billing","claims","notif"]:
    arrow(xs[k]+sw/2, dby+dbh, xs[k]+sw/2, 720, color=ORG, dash=True)

# --- Key sync inter-service calls (dashed blue) ---
BLU="#1971c2"
arrow(xs["order"]+sw, sy+20, px, sy+20, color=BLU, dash=True)        # order -> pricing
label(xs["order"]+sw+8, sy-2, "quote", 11, BLU, 80)

# --- Legend ---
ly = 840
label(60, ly, "── sync (HTTP+JWT)", 13, "#1e1e1e", 200)
label(60, ly+22, "── async events (RabbitMQ/outbox)", 13, ORG, 300)
label(60, ly+44, "── inter-service sync call", 13, BLU, 250)

doc = {
    "type": "excalidraw", "version": 2, "source": "https://excalidraw.com",
    "elements": elements,
    "appState": {"gridSize": None, "viewBackgroundColor": "#ffffff"},
    "files": {},
}

with open("architecture.excalidraw", "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=2)

print(f"Wrote architecture.excalidraw with {len(elements)} elements")
