package com.fenglei.smartpetcollar;

public class LocationData {

    private String type;
    private String id;
    private String act;
    private int dur;
    private GnssData data;

    public static class GnssData {
        private double lat;
        private double lon;
        private int sat;

        public GnssData() {}

        public GnssData(double lat, double lon, int sat) {
            this.lat = lat;
            this.lon = lon;
            this.sat = sat;
        }

        public double getLat() { return lat; }
        public void setLat(double lat) { this.lat = lat; }

        public double getLon() { return lon; }
        public void setLon(double lon) { this.lon = lon; }

        public int getSat() { return sat; }
        public void setSat(int sat) { this.sat = sat; }

        public boolean isValidGnssFix() {
            return sat > 3;
        }

        @Override
        public String toString() {
            return "Lat: " + lat + ", Lon: " + lon + ", Sat: " + sat
                    + (isValidGnssFix() ? " (Valid)" : " (Invalid)");
        }
    }

    public LocationData() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAct() { return act; }
    public void setAct(String act) { this.act = act; }

    public int getDur() { return dur; }
    public void setDur(int dur) { this.dur = dur; }

    public GnssData getData() { return data; }
    public void setData(GnssData data) { this.data = data; }

    public String getLocationTypeDisplay() {
        if ("gnss".equals(type)) {
            return "GNSS (GPS/BeiDou)";
        } else if ("cell".equals(type)) {
            return "Cell (Base Station)";
        }
        return type;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(getLocationTypeDisplay());
        sb.append(", ID: ").append(id);
        sb.append(", Act: ").append(act);
        sb.append(", Dur: ").append(dur).append("s");
        if (data != null) {
            sb.append("\n  ").append(data.toString());
        }
        return sb.toString();
    }
}
