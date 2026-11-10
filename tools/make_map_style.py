#!/usr/bin/env python3
"""Builds app/src/main/assets/map_dark.json — MockRoute's dark, detailed map.

Usage:
  curl -o liberty.json https://tiles.openfreemap.org/styles/liberty
  python3 tools/make_map_style.py liberty.json app/src/main/assets/map_dark.json

Since 1.1.0 the source is OpenFreeMap's "liberty" style (not "dark"): it carries every OpenMapTiles layer a
Google-Maps-like map needs (all buildings, 3-D heights, shops/POIs with icons, landuse kinds, bridges/tunnels,
rail, road shields). This script repaints it on MockRoute's dark base and adds a few layers:
  * buildings from zoom 13 (flat) and in 3-D from zoom 14, LIGHTER than the streets, taller = lighter;
  * POI labels coloured by category (food orange, shops blue, parks green, health pink, transit sky,
    culture/education lilac), like Google Maps;
  * POIs from zoom 14 (all ranks by 16), house numbers from zoom 17;
  * local names first (the user's own language on the street), English as fallback;
  * blue water, dark-green parks/woods/grass.
"""
import json
import sys

src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src))
d["name"] = "MockRoute dark"

BG = "#10151C"
HALO = "#0B0F14"

# ---- colours -----------------------------------------------------------------------------------------------
FILL = {
    "park": ("#163D28", 0.85),
    "landuse_residential": ("#141A22", 0.9),
    "landcover_wood": ("#123522", 0.9),
    "landcover_grass": ("#173F2A", 0.7),
    "landcover_ice": ("#1C2632", 0.9),
    "landcover_sand": ("#2B2A22", 0.8),
    "landuse_pitch": ("#1A4430", 1.0),
    "landuse_track": ("#1A4430", 1.0),
    "landuse_cemetery": ("#18352A", 1.0),
    "landuse_hospital": ("#33212B", 1.0),
    "landuse_school": ("#2A2738", 1.0),
    "water": ("#1B4F9C", 1.0),
    "aeroway_fill": ("#1B2129", 1.0),
}
ROAD = {  # inner line colours by road class
    "motorway": "#7A6642",      # muted amber, like the big roads on Google's dark map
    "motorway_link": "#6B5A3C",
    "trunk_primary": "#4C5668",
    "secondary_tertiary": "#414A59",
    "link": "#414A59",
    "street": "#323A46", "minor": "#323A46",
    "service_track": "#2A313B",
    "path_pedestrian": "#3E4756",
}
CASING = "#0E1217"
RAIL = "#4B4560"
WATER_LINE = "#2462B8"


def road_kind(lid):
    for k in ("motorway_link", "motorway", "trunk_primary", "secondary_tertiary", "service_track",
              "path_pedestrian", "street", "minor", "link"):
        if k in lid:
            return k
    return None


# Local names first (street signs say "Calle …", not "… Street").
NAME_PT = ["coalesce", ["get", "name"], ["get", "name:latin"], ["get", "name_en"]]


def localise(layout):
    tf = layout.get("text-field")
    if isinstance(tf, list) and "name:nonlatin" in json.dumps(tf):
        layout["text-field"] = NAME_PT


POI_COLOR = ["match", ["get", "class"],
             ["restaurant", "fast_food", "cafe", "bar", "beer", "ice_cream", "bakery", "pub"], "#F2A65A",
             ["shop", "grocery", "clothing_store", "shoe", "jewelry", "alcohol_shop", "furniture", "florist",
              "hardware", "laundry", "car", "bicycle", "music", "mobile_phone", "books", "gift", "department_store",
              "convenience", "supermarket", "toys", "optician", "hairdresser"], "#6FA8F5",
             ["park", "garden", "campsite", "playground", "zoo", "golf", "pitch", "stadium", "swimming"], "#5CC489",
             ["hospital", "doctors", "pharmacy", "dentist", "veterinary"], "#F27B95",
             ["bus", "railway", "rail", "ferry_terminal", "airport", "aerialway", "fuel", "parking", "bicycle_rental"],
             "#7FB2F0",
             ["school", "college", "library", "museum", "art_gallery", "theatre", "cinema", "town_hall",
              "place_of_worship", "monument", "castle", "attraction"], "#B9A3F0",
             "#9AA6B4"]

out = []
for layer in d["layers"]:
    lid, ltype = layer["id"], layer["type"]
    paint = layer.setdefault("paint", {})
    layout = layer.setdefault("layout", {})

    if lid == "background":
        paint["background-color"] = BG
    elif lid == "natural_earth":
        paint["raster-brightness-max"] = 0.28
        paint["raster-saturation"] = -0.6
    elif lid in FILL:
        color, opacity = FILL[lid]
        paint.pop("fill-pattern", None)
        paint["fill-color"] = color
        paint["fill-opacity"] = opacity
        paint.pop("fill-outline-color", None)
    elif lid == "park_outline":
        paint["line-color"] = "#1F5236"
    elif lid == "landcover_wetland":
        paint.pop("fill-pattern", None)
        paint["fill-color"] = "#16362F"
        paint["fill-opacity"] = 0.8
    elif lid == "road_area_pattern":
        paint.pop("fill-pattern", None)
        paint["fill-color"] = "#262D37"
    elif lid.startswith("waterway"):
        if ltype == "line":
            paint["line-color"] = WATER_LINE
    elif lid.startswith("aeroway"):
        if ltype == "line":
            paint["line-color"] = "#2C333D"
    elif lid.startswith(("road_", "tunnel_", "bridge_")) and ltype == "line":
        if "rail" in lid:
            paint["line-color"] = RAIL
        elif "casing" in lid:
            paint["line-color"] = "#1A2029" if lid.startswith("bridge_") else CASING
            if "path_pedestrian" in lid:
                paint["line-color"] = "#1A2029"
        else:
            kind = road_kind(lid)
            if kind:
                paint["line-color"] = ROAD[kind]
            if lid.startswith("tunnel_"):
                paint["line-opacity"] = 0.6
    elif lid in ("road_one_way_arrow", "road_one_way_arrow_opposite"):
        paint["icon-opacity"] = 0.45
    elif lid == "building":
        # Flat footprints from zoom 13 (as soon as the tiles carry them) until the 3-D layer takes over.
        layer["minzoom"] = 13
        layer["maxzoom"] = 14
        paint["fill-color"] = "#485363"
        paint["fill-outline-color"] = "#5A6677"
        paint["fill-opacity"] = ["interpolate", ["linear"], ["zoom"], 13, 0.6, 14, 1]
    elif lid == "building-3d":
        layer["minzoom"] = 14
        paint["fill-extrusion-color"] = ["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 6],
                                         0, "#4E5A6A", 15, "#5A6779", 40, "#6C7B8F", 120, "#8796AB"]
        paint["fill-extrusion-height"] = ["interpolate", ["linear"], ["zoom"], 14, 0, 14.6,
                                          ["coalesce", ["get", "render_height"], 6]]
        paint["fill-extrusion-base"] = ["coalesce", ["get", "render_min_height"], 0]
        paint["fill-extrusion-opacity"] = 0.95
        paint["fill-extrusion-vertical-gradient"] = True
    elif lid.startswith("boundary"):
        paint["line-color"] = "#5B6675"
    elif ltype == "symbol":
        localise(layout)
        if lid.startswith("poi_") and lid != "poi_transit":
            layer["minzoom"] = {"poi_r1": 14, "poi_r7": 15, "poi_r20": 16}.get(lid, layer.get("minzoom"))
            # The sprite icons carry their own light badge; the label takes the category colour.
            paint["text-color"] = POI_COLOR
            paint["text-halo-color"] = HALO
            paint["text-halo-width"] = 1.3
            paint["text-halo-blur"] = 0.3
            layout["text-font"] = ["Noto Sans Regular"]
            layout["text-size"] = 11.5
        elif lid == "poi_transit":
            paint["text-color"] = "#7FB2F0"
            paint["text-halo-color"] = HALO
            paint["text-halo-width"] = 1.3
        elif lid.startswith("highway-name"):
            paint["text-color"] = "#AEB8C4" if lid != "highway-name-path" else "#8592A1"
            paint["text-halo-color"] = HALO
            paint["text-halo-width"] = 1.4
            layer["minzoom"] = {"highway-name-minor": 14.5, "highway-name-major": 12}.get(lid, layer.get("minzoom"))
        elif lid.startswith(("water", "waterway")):
            paint["text-color"] = "#8FB8F5"
            paint["text-halo-color"] = "#0F2F5E"
            paint["text-halo-width"] = 1.2
        elif lid.startswith(("highway-shield", "road_shield")):
            pass  # shields keep their own sprite look
        else:  # places, airports
            paint["text-color"] = "#DCE3EA" if lid not in ("label_other", "label_state") else "#A9B4C0"
            paint["text-halo-color"] = HALO
            paint["text-halo-width"] = 1.5
            if "icon-image" in layout and "circle_11_black" in json.dumps(layout["icon-image"]):
                paint["icon-opacity"] = 0  # the black dots vanish on a dark map anyway

    out.append(layer)

    if lid == "building-3d":
        # House numbers on top of the buildings (OpenMapTiles "housenumber", zoom 14+ in the tiles).
        out.append({
            "id": "housenumber", "type": "symbol", "source": layer["source"], "source-layer": "housenumber",
            "minzoom": 17,
            "layout": {"text-field": ["to-string", ["get", "housenumber"]], "text-font": ["Noto Sans Regular"],
                       "text-size": 10, "text-padding": 2},
            "paint": {"text-color": "#8592A1", "text-halo-color": HALO, "text-halo-width": 1},
        })

# Labels on top of the 3-D buildings: move every symbol layer after the last non-symbol layer.
non_sym = [l for l in out if l["type"] != "symbol"]
sym = [l for l in out if l["type"] == "symbol"]
d["layers"] = non_sym + sym
json.dump(d, open(dst, "w"), separators=(",", ":"))
print(len(d["layers"]), "layers ->", dst)
