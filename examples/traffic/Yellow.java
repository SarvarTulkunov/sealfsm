package examples.traffic;

public final class Yellow implements TrafficLight {
    @Override
    public TrafficLight next() {
        return new Red();
    }
}
