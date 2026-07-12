package examples.traffic;

public final class Red implements TrafficLight {
    @Override
    public TrafficLight next() {
        return new Green();
    }
}
