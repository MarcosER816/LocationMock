#!/usr/bin/env python3
"""Builds app/src/main/assets/map_dark.json from OpenFreeMap's "dark" style.

Usage: python3 tools/make_map_style.py <downloaded dark.json> app/src/main/assets/map_dark.json
(download: https://tiles.openfreemap.org/styles/dark). Recolours it for MockRoute: blue water, dark-green parks and
woods, streets in dark greys, buildings LIGHTER than the streets and extruded in 3-D, brighter labels.
"""
import json, sys

src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src))
d["name"] = "MockRoute dark"

FILL = {
    "water": "#1B4F9C",
    "landcover_wood": "#123A22",
    "landuse_park": "#17482A",
    "landuse_residential": "#11161D",
    "landcover_ice_shelf": "#1A222C",
    "landcover_glacier": "#1A222C",
    "aeroway-area": "#1A2028",
    "road_area_pier": "#1A2028",
}
LINE = {
    "waterway": "#2462B8",
    "highway_path": "#333B46",
    "highway_minor": "#333B46",
    "highway_major_casing": "#20262E",
    "highway_major_inner": "#3E4753",
    "highway_major_subtle": "#3E4753",
    "highway_motorway_casing": "#20262E",
    "highway_motorway_inner": "#4A5563",
    "highway_motorway_subtle": "#4A5563",
    "road_pier": "#1A2028",
    "aeroway-taxiway": "#2A313A",
    "aeroway-runway": "#2A313A",
    "railway": "#3A3340", "railway_minor": "#3A3340", "railway_transit": "#3A3340",
    "boundary_state": "#4A5563", "boundary_country_z0-4": "#5B6675", "boundary_country_z5-": "#5B6675",
}
BUILDING = "#66717F"      # lighter than every street colour above
BUILDING_3D = "#7A8696"

out = []
for layer in d["layers"]:
    lid, paint = layer["id"], layer.setdefault("paint", {})
    if lid == "background":
        paint["background-color"] = "#0D1117"
    elif lid in FILL:
        paint["fill-color"] = FILL[lid]
        paint.pop("fill-outline-color", None)
        if lid in ("landcover_wood", "landuse_park"):
            paint["fill-opacity"] = 0.9
    elif lid in LINE:
        paint["line-color"] = LINE[lid]
    elif lid == "building":
        paint["fill-color"] = BUILDING
        paint["fill-outline-color"] = "#4E5865"
        paint.pop("fill-antialias", None)
        layer["maxzoom"] = 15  # from zoom 15 the 3-D layer takes over
    elif layer["type"] == "symbol" and "text-color" in paint:
        if lid == "water_name":
            paint["text-color"] = "#8FB8F5"
            paint["text-halo-color"] = "#123A70"
        elif lid.startswith("highway_name"):
            paint["text-color"] = "#A9B4C0"
            paint["text-halo-color"] = "#0D1117"
            paint["text-halo-width"] = 1.2
        else:
            paint["text-color"] = "#D5DCE4"
            paint["text-halo-color"] = "#0D1117"
            paint["text-halo-width"] = 1.4
    out.append(layer)
    if lid == "building":
        # 3-D buildings (OpenMapTiles render_height / render_min_height), visible when the map is tilted.
        out.append({
            "id": "building-3d", "type": "fill-extrusion", "source": layer["source"], "source-layer": "building",
            "minzoom": 15,
            "paint": {
                "fill-extrusion-color": BUILDING_3D,
                "fill-extrusion-height": ["coalesce", ["get", "render_height"], 6],
                "fill-extrusion-base": ["coalesce", ["get", "render_min_height"], 0],
                "fill-extrusion-opacity": 0.9,
            },
        })
d["layers"] = out
json.dump(d, open(dst, "w"), separators=(",", ":"))
print(len(out), "layers ->", dst)
