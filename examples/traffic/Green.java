package examples.traffic;

public final class Green implements TrafficLight {
    @Override
    public TrafficLight next() {
        return new Yellow();
    }
}
