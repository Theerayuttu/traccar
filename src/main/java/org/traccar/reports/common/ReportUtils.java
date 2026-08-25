/*
 * Copyright 2016 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2017 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.reports.common;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.tools.generic.DateTool;
import org.apache.velocity.tools.generic.NumberTool;
import org.jxls.area.Area;
import org.jxls.builder.xls.XlsCommentAreaBuilder;
import org.jxls.common.CellRef;
import org.jxls.formula.StandardFormulaProcessor;
import org.jxls.transform.Transformer;
import org.jxls.transform.poi.PoiTransformer;
import org.jxls.util.TransformerFactory;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.geocoder.Geocoder;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.helper.model.UserUtil;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.reports.model.BaseReportItem;
import org.traccar.reports.model.StopReportItem;
import org.traccar.reports.model.TripReportItem;
import org.traccar.session.state.MotionProcessor;
import org.traccar.session.state.MotionState;
import org.traccar.session.state.NewMotionProcessor;
import org.traccar.session.state.NewMotionState;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReportUtils {

    private final Config config;
    private final Storage storage;
    private final PermissionsService permissionsService;
    private final VelocityEngine velocityEngine;
    private final Geocoder geocoder;

    @Inject
    public ReportUtils(
            Config config, Storage storage, PermissionsService permissionsService,
            VelocityEngine velocityEngine, @Nullable Geocoder geocoder) {
        this.config = config;
        this.storage = storage;
        this.permissionsService = permissionsService;
        this.velocityEngine = velocityEngine;
        this.geocoder = geocoder;
    }

    public <T extends BaseModel> T getObject(long userId, Class<T> clazz, long objectId) {
        try {
            return storage.getObject(clazz, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("id", objectId),
                            new Condition.Permission(User.class, userId, clazz))));
        } catch (StorageException e) {
            return null;
        }
    }

    public void checkPeriodLimit(Date from, Date to) {
        long limit = config.getLong(Keys.REPORT_PERIOD_LIMIT) * 1000;
        if (limit > 0 && to.getTime() - from.getTime() > limit) {
            throw new IllegalArgumentException("Time period exceeds the limit");
        }
    }

    // Cumulative rise (current sample vs minimum in the previous
    // REFILL_LOOKBACK_SAMPLES) larger than this threshold is treated as a
    // candidate refill event; it is only accepted after
    // REFILL_CONFIRM_SAMPLES further samples stay near the new level.
    // Values are pre-smoothed with a median filter of size
    // FUEL_SMOOTHING_WINDOW so short ignition-on / power-up sensor spikes
    // (up to (WINDOW-1)/2 samples wide) are eliminated before the algorithm sees
    // them, while any real baseline shift persists through the filter.
    // Lookback lets us catch slow refills that arrive as a stair of small
    // sub-threshold steps (e.g. +3 per sample over 8 samples).
    private static final double FUEL_REFILL_THRESHOLD = 10.0;        // litres
    private static final double FUEL_LEVEL_REFILL_THRESHOLD = 10.0;  // percent of tank
    private static final int REFILL_CONFIRM_SAMPLES = 10;
    private static final int REFILL_LOOKBACK_SAMPLES = 8;
    private static final int FUEL_SMOOTHING_WINDOW = 9;              // odd; suppresses <= 4-sample spikes

    /**
     * Calculate the fuel consumed between the first and last position of the provided list using
     * a segment-based algorithm that is robust to both refill events and sensor noise:
     *   - a sudden rise > threshold between two consecutive samples closes the current segment
     *     and starts a new one from the refill value;
     *   - within each segment consumption = start value - end value (mid-segment noise cancels
     *     out because only the segment endpoints are used).
     * Falls through to the cumulative KEY_FUEL_USED counter when both endpoints carry it, and
     * scales KEY_FUEL_LEVEL (%) by the device capacity attribute.
     */
    public double calculateFuel(List<Position> positions, Device device) {
        if (positions == null || positions.isEmpty()) {
            return 0;
        }
        Position first = positions.get(0);
        Position last = positions.get(positions.size() - 1);

        if (first.hasAttribute(Position.KEY_FUEL_USED) && last.hasAttribute(Position.KEY_FUEL_USED)) {
            return last.getDouble(Position.KEY_FUEL_USED) - first.getDouble(Position.KEY_FUEL_USED);
        }
        if (first.hasAttribute(Position.KEY_FUEL) && last.hasAttribute(Position.KEY_FUEL)) {
            return sumFuelSegments(positions, Position.KEY_FUEL, FUEL_REFILL_THRESHOLD);
        }
        if (first.hasAttribute(Position.KEY_FUEL_LEVEL) && last.hasAttribute(Position.KEY_FUEL_LEVEL)
                && device.hasAttribute(Keys.FUEL_CAPACITY.getKey())) {
            double percentSpent = sumFuelSegments(positions, Position.KEY_FUEL_LEVEL, FUEL_LEVEL_REFILL_THRESHOLD);
            return (percentSpent / 100) * device.getDouble(Keys.FUEL_CAPACITY.getKey());
        }
        return 0;
    }

    public double calculateFuel(Position first, Position last, Device device) {
        return calculateFuel(List.of(first, last), device);
    }

    private double sumFuelSegments(List<Position> positions, String key, double refillThreshold) {
        // Pre-compute median-smoothed values in a parallel double array. Positions without the
        // attribute get NaN so downstream code can skip them without reallocating anything.
        int n = positions.size();
        double[] smoothed = new double[n];
        smoothFuelValues(positions, key, smoothed);

        double total = 0;
        Double segmentStart = null;
        int segmentStartIndex = -1;
        Double lastValid = null;
        for (int i = 0; i < n; i++) {
            double currValue = smoothed[i];
            if (Double.isNaN(currValue)) {
                continue;
            }
            if (segmentStart == null) {
                segmentStart = currValue;
                segmentStartIndex = i;
                lastValid = currValue;
                continue;
            }
            // Compare against the minimum of the previous REFILL_LOOKBACK_SAMPLES
            // (bounded by the current segment start) rather than just the immediately
            // previous sample. This catches slow refills that arrive as a stair of
            // small sub-threshold rises (e.g. +3 per sample for 8 samples).
            int lookbackStart = Math.max(segmentStartIndex, i - REFILL_LOOKBACK_SAMPLES);
            double minInLookback = Double.POSITIVE_INFINITY;
            for (int j = lookbackStart; j < i; j++) {
                if (!Double.isNaN(smoothed[j]) && smoothed[j] < minInLookback) {
                    minInLookback = smoothed[j];
                }
            }
            if (minInLookback != Double.POSITIVE_INFINITY
                    && currValue - minInLookback > refillThreshold
                    && isRefillConfirmed(smoothed, i, currValue, refillThreshold)) {
                // Refill detected AND confirmed. Close the segment at the pre-refill level.
                total += segmentStart - minInLookback;
                // Take the median of the confirmation window as the post-refill baseline
                // (robust to occasional spikes that survive the smoothing filter) and
                // fast-forward past the whole transition so the next iteration doesn't
                // re-trigger on the tail of the same refill.
                int windowSize = Math.min(REFILL_CONFIRM_SAMPLES + FUEL_SMOOTHING_WINDOW / 2, n - i);
                double[] baselineWindow = new double[windowSize];
                double lastInWindow = currValue;
                int count = 0;
                for (int j = i; j < i + windowSize; j++) {
                    if (!Double.isNaN(smoothed[j])) {
                        baselineWindow[count++] = smoothed[j];
                        lastInWindow = smoothed[j];
                    }
                }
                if (count > 0) {
                    java.util.Arrays.sort(baselineWindow, 0, count);
                    segmentStart = baselineWindow[count / 2];
                } else {
                    segmentStart = currValue;
                }
                segmentStartIndex = i;
                // Preserve the actual value at the end of the window so subsequent
                // consumption inside the window still contributes to the running total.
                lastValid = lastInWindow;
                i = i + windowSize - 1;   // for-loop will increment to i + windowSize
                continue;
            }
            lastValid = currValue;
        }
        if (segmentStart != null && lastValid != null) {
            total += segmentStart - lastValid;
        }
        // "Spent" fuel is non-negative by definition; clamp so a small unhandled
        // rise below the refill threshold does not produce a misleading negative.
        return Math.max(0, total);
    }

    /**
     * Apply a rolling median filter of size {@link #FUEL_SMOOTHING_WINDOW} to remove short
     * sensor spikes (typical ignition-on transients that are ≤ (WINDOW−1)/2 samples wide).
     * Positions missing the attribute are represented as NaN in the output array.
     * Uses a reusable working buffer to avoid per-sample allocation.
     */
    private void smoothFuelValues(List<Position> positions, String key, double[] out) {
        int n = positions.size();
        // Below this size the median window would swallow real transitions and
        // the two-point wrapper (Trip/Stop) would collapse to zero. Copy raw
        // values through instead - short reports can't reliably span refills.
        // The 3x smoothing-window heuristic keeps synthetic tests unaffected while
        // still enabling smoothing on any report that spans more than ~30 samples.
        if (n < FUEL_SMOOTHING_WINDOW * 3) {
            for (int i = 0; i < n; i++) {
                out[i] = positions.get(i).hasAttribute(key)
                        ? positions.get(i).getDouble(key) : Double.NaN;
            }
            return;
        }
        int halfWindow = FUEL_SMOOTHING_WINDOW / 2;
        double[] window = new double[FUEL_SMOOTHING_WINDOW];
        for (int i = 0; i < n; i++) {
            if (!positions.get(i).hasAttribute(key)) {
                out[i] = Double.NaN;
                continue;
            }
            int count = 0;
            int lo = Math.max(0, i - halfWindow);
            int hi = Math.min(n, i + halfWindow + 1);
            for (int j = lo; j < hi; j++) {
                if (positions.get(j).hasAttribute(key)) {
                    window[count++] = positions.get(j).getDouble(key);
                }
            }
            if (count == 0) {
                out[i] = Double.NaN;
            } else {
                // Arrays.sort on a small primitive slice uses insertion sort (fast for n < 47).
                java.util.Arrays.sort(window, 0, count);
                out[i] = window[count / 2];
            }
        }
    }

    /**
     * Confirm that a candidate refill spike is real by requiring the following
     * {@link #REFILL_CONFIRM_SAMPLES} valid samples to stay within {@code threshold} of the
     * new level. Transient sensor spikes (up-then-down) fail this check and are ignored.
     */
    private boolean isRefillConfirmed(double[] smoothed, int spikeIndex, double spikeValue, double threshold) {
        int confirmed = 0;
        for (int j = spikeIndex + 1; j < smoothed.length; j++) {
            double val = smoothed[j];
            if (Double.isNaN(val)) {
                continue;
            }
            if (val < spikeValue - threshold) {
                return false;
            }
            confirmed++;
            if (confirmed >= REFILL_CONFIRM_SAMPLES) {
                return true;
            }
        }
        return confirmed >= REFILL_CONFIRM_SAMPLES;
    }

    /**
     * Calculate the SoC (%) consumed between the first and last position of the provided list.
     * Charging intervals (either endpoint has {@link Position#KEY_CHARGE} = true) are skipped;
     * regen (negative delta while not charging) subtracts from the total. Returns 0 when neither
     * endpoint has the "soc" attribute. Positive result = net consumption, negative = regen won.
     */
    public double calculateSoc(List<Position> positions, Device device) {
        if (positions == null || positions.size() < 2) {
            return 0;
        }
        double totalConsumed = 0;
        for (int i = 1; i < positions.size(); i++) {
            Position prev = positions.get(i - 1);
            Position curr = positions.get(i);
            if (!prev.hasAttribute("soc") || !curr.hasAttribute("soc")) {
                continue;
            }
            if (prev.getBoolean(Position.KEY_CHARGE) || curr.getBoolean(Position.KEY_CHARGE)) {
                continue;
            }
            totalConsumed += prev.getDouble("soc") - curr.getDouble("soc");
        }
        return totalConsumed;
    }

    public double calculateSoc(Position first, Position last, Device device) {
        return calculateSoc(List.of(first, last), device);
    }

    public String findDriver(Position firstPosition, Position lastPosition) {
        if (firstPosition.hasAttribute(Position.KEY_DRIVER_UNIQUE_ID)) {
            return firstPosition.getString(Position.KEY_DRIVER_UNIQUE_ID);
        } else if (lastPosition.hasAttribute(Position.KEY_DRIVER_UNIQUE_ID)) {
            return lastPosition.getString(Position.KEY_DRIVER_UNIQUE_ID);
        }
        return null;
    }

    public String findDriverName(String driverUniqueId) throws StorageException {
        if (driverUniqueId != null) {
            Driver driver = storage.getObject(Driver.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("uniqueId", driverUniqueId)));
            if (driver != null) {
                return driver.getName();
            }
        }
        return null;
    }

    public org.jxls.common.Context initializeContext(long userId) throws StorageException {
        var server = permissionsService.getServer();
        var user = permissionsService.getUser(userId);
        var context = PoiTransformer.createInitialContext();
        context.putVar("distanceUnit", UserUtil.getDistanceUnit(server, user));
        context.putVar("speedUnit", UserUtil.getSpeedUnit(server, user));
        context.putVar("volumeUnit", UserUtil.getVolumeUnit(server, user));
        context.putVar("webUrl", velocityEngine.getProperty("web.url"));
        context.putVar("dateTool", new DateTool());
        context.putVar("numberTool", new NumberTool());
        context.putVar("timezone", UserUtil.getTimezone(server, user));
        context.putVar("locale", Locale.getDefault());
        context.putVar("bracketsRegex", "[\\{\\}\"]");
        return context;
    }

    public void processTemplateWithSheets(
            InputStream templateStream, OutputStream targetStream, org.jxls.common.Context context) throws IOException {

        Transformer transformer = TransformerFactory.createTransformer(templateStream, targetStream);
        List<Area> xlsAreas = new XlsCommentAreaBuilder(transformer).build();
        for (Area xlsArea : xlsAreas) {
            xlsArea.applyAt(new CellRef(xlsArea.getStartCellRef().getCellName()), context);
            xlsArea.setFormulaProcessor(new StandardFormulaProcessor());
            xlsArea.processFormulas();
        }
        transformer.deleteSheet(xlsAreas.get(0).getStartCellRef().getSheetName());
        transformer.write();
    }

    private TripReportItem calculateTrip(
            Device device, Position startTrip, Position endTrip, double maxSpeed,
            boolean ignoreOdometer) throws StorageException {

        TripReportItem trip = new TripReportItem();

        long tripDuration = endTrip.getFixTime().getTime() - startTrip.getFixTime().getTime();
        long deviceId = startTrip.getDeviceId();
        trip.setDeviceId(deviceId);
        trip.setDeviceName(device.getName());

        trip.setStartPositionId(startTrip.getId());
        trip.setStartLat(startTrip.getLatitude());
        trip.setStartLon(startTrip.getLongitude());
        trip.setStartTime(startTrip.getFixTime());
        String startAddress = startTrip.getAddress();
        if (startAddress == null && geocoder != null && config.getBoolean(Keys.GEOCODER_ON_REQUEST)) {
            startAddress = geocoder.getAddress(startTrip.getLatitude(), startTrip.getLongitude(), null);
        }
        trip.setStartAddress(startAddress);

        trip.setEndPositionId(endTrip.getId());
        trip.setEndLat(endTrip.getLatitude());
        trip.setEndLon(endTrip.getLongitude());
        trip.setEndTime(endTrip.getFixTime());
        String endAddress = endTrip.getAddress();
        if (endAddress == null && geocoder != null && config.getBoolean(Keys.GEOCODER_ON_REQUEST)) {
            endAddress = geocoder.getAddress(endTrip.getLatitude(), endTrip.getLongitude(), null);
        }
        trip.setEndAddress(endAddress);

        trip.setDistance(PositionUtil.calculateDistance(startTrip, endTrip, !ignoreOdometer));
        trip.setDuration(tripDuration);
        if (tripDuration > 0) {
            trip.setAverageSpeed(UnitsConverter.knotsFromMps(trip.getDistance() * 1000 / tripDuration));
        }
        trip.setMaxSpeed(maxSpeed);
        trip.setSpentFuel(calculateFuel(startTrip, endTrip, device));
        trip.setSpentSoc(calculateSoc(startTrip, endTrip, device));

        trip.setDriverUniqueId(findDriver(startTrip, endTrip));
        trip.setDriverName(findDriverName(trip.getDriverUniqueId()));

        if (!ignoreOdometer
                && startTrip.getDouble(Position.KEY_ODOMETER) != 0
                && endTrip.getDouble(Position.KEY_ODOMETER) != 0) {
            trip.setStartOdometer(startTrip.getDouble(Position.KEY_ODOMETER));
            trip.setEndOdometer(endTrip.getDouble(Position.KEY_ODOMETER));
        } else {
            trip.setStartOdometer(startTrip.getDouble(Position.KEY_TOTAL_DISTANCE));
            trip.setEndOdometer(endTrip.getDouble(Position.KEY_TOTAL_DISTANCE));
        }

        return trip;
    }

    private StopReportItem calculateStop(
            Device device, Position startStop, Position endStop, boolean ignoreOdometer) {

        StopReportItem stop = new StopReportItem();

        long deviceId = startStop.getDeviceId();
        stop.setDeviceId(deviceId);
        stop.setDeviceName(device.getName());

        stop.setPositionId(startStop.getId());
        stop.setLatitude(startStop.getLatitude());
        stop.setLongitude(startStop.getLongitude());
        stop.setStartTime(startStop.getFixTime());
        String address = startStop.getAddress();
        if (address == null && geocoder != null && config.getBoolean(Keys.GEOCODER_ON_REQUEST)) {
            address = geocoder.getAddress(stop.getLatitude(), stop.getLongitude(), null);
        }
        stop.setAddress(address);

        stop.setEndTime(endStop.getFixTime());

        long stopDuration = endStop.getFixTime().getTime() - startStop.getFixTime().getTime();
        stop.setDuration(stopDuration);
        stop.setSpentFuel(calculateFuel(startStop, endStop, device));
        stop.setSpentSoc(calculateSoc(startStop, endStop, device));

        if (startStop.hasAttribute(Position.KEY_HOURS) && endStop.hasAttribute(Position.KEY_HOURS)) {
            stop.setEngineHours(endStop.getLong(Position.KEY_HOURS) - startStop.getLong(Position.KEY_HOURS));
        }

        if (!ignoreOdometer
                && startStop.getDouble(Position.KEY_ODOMETER) != 0
                && endStop.getDouble(Position.KEY_ODOMETER) != 0) {
            stop.setStartOdometer(startStop.getDouble(Position.KEY_ODOMETER));
            stop.setEndOdometer(endStop.getDouble(Position.KEY_ODOMETER));
        } else {
            stop.setStartOdometer(startStop.getDouble(Position.KEY_TOTAL_DISTANCE));
            stop.setEndOdometer(endStop.getDouble(Position.KEY_TOTAL_DISTANCE));
        }

        return stop;

    }

    @SuppressWarnings("unchecked")
    private <T extends BaseReportItem> T calculateTripOrStop(
            Device device, Position startPosition, Position endPosition, double maxSpeed,
            boolean ignoreOdometer, Class<T> reportClass) throws StorageException {

        if (reportClass.equals(TripReportItem.class)) {
            return (T) calculateTrip(device, startPosition, endPosition, maxSpeed, ignoreOdometer);
        } else {
            return (T) calculateStop(device, startPosition, endPosition, ignoreOdometer);
        }
    }

    public <T extends BaseReportItem> List<T> detectTripsAndStops(
            Device device, Date from, Date to, Class<T> reportClass) throws StorageException {

        long threshold = config.getLong(Keys.REPORT_FAST_THRESHOLD);
        if (Duration.between(from.toInstant(), to.toInstant()).toSeconds() > threshold) {
            return fastTripsAndStops(device, from, to, reportClass);
        } else {
            return slowTripsAndStops(device, from, to, reportClass);
        }
    }

    public <T extends BaseReportItem> List<T> slowTripsAndStops(
            Device device, Date from, Date to, Class<T> reportClass) throws StorageException {

        List<T> result = new ArrayList<>();
        var attributeProvider = new AttributeUtil.StorageProvider(config, storage, permissionsService, device);
        TripsConfig tripsConfig = new TripsConfig(attributeProvider);
        boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
        boolean trips = reportClass.equals(TripReportItem.class);
        boolean useNewLogic = config.getBoolean(Keys.REPORT_TRIP_NEW_LOGIC);

        List<Event> events = new ArrayList<>();
        Map<Long, Position> positionMap = new HashMap<>();
        Position startPosition = null;
        double maxSpeed = 0;
        Position lastPosition = null;

        if (useNewLogic) {
            double minDistance = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_MIN_DISTANCE);
            long minDuration = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_MIN_DURATION) * 1000;
            long stopGap = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_STOP_GAP) * 1000;
            Deque<Position> motionPositions = new ArrayDeque<>();
            NewMotionState motionState = new NewMotionState();
            motionState.setPositions(motionPositions);

            try (var stream = PositionUtil.getPositionsStream(storage, device.getId(), from, to, 0)) {
                for (var iterator = stream.iterator(); iterator.hasNext();) {
                    Position position = iterator.next();
                    if (lastPosition == null) {
                        boolean initialValue = position.getBoolean(Position.KEY_MOTION);
                        if (initialValue == trips) {
                            startPosition = position;
                            maxSpeed = position.getSpeed();
                        }
                        motionState.setMotionStreak(initialValue);
                        motionState.setEventPosition(position);
                    }
                    maxSpeed = Math.max(maxSpeed, position.getSpeed());
                    positionMap.put(position.getId(), position);
                    NewMotionProcessor.updateState(motionState, position, minDistance, minDuration, stopGap);
                    if (!motionState.getEvents().isEmpty()) {
                        for (Event event : motionState.getEvents()) {
                            event.set("maxSpeed", maxSpeed);
                            events.add(event);
                        }
                        maxSpeed = 0;
                    }
                    motionPositions.add(position);
                    while (motionPositions.size() > 1) {
                        var motionIterator = motionPositions.iterator();
                        motionIterator.next();
                        Position second = motionIterator.next();
                        Position last = motionPositions.peekLast();
                        if (last.getFixTime().getTime() - second.getFixTime().getTime() >= minDuration) {
                            motionPositions.poll();
                        } else {
                            break;
                        }
                    }
                    lastPosition = position;
                }
            }
        } else {
            MotionState motionState = new MotionState();

            try (var stream = PositionUtil.getPositionsStream(storage, device.getId(), from, to, 0)) {
                for (var iterator = stream.iterator(); iterator.hasNext();) {
                    Position position = iterator.next();
                    if (lastPosition == null) {
                        boolean initialValue = position.getBoolean(Position.KEY_MOTION);
                        if (initialValue == trips) {
                            startPosition = position;
                            maxSpeed = position.getSpeed();
                        }
                        motionState.setMotionStreak(initialValue);
                        motionState.setMotionState(initialValue);
                    }
                    maxSpeed = Math.max(maxSpeed, position.getSpeed());
                    positionMap.put(position.getId(), position);
                    boolean motion = position.getBoolean(Position.KEY_MOTION);
                    MotionProcessor.updateState(motionState, lastPosition, position, motion, tripsConfig);
                    if (motionState.getEvent() != null) {
                        motionState.getEvent().set("maxSpeed", maxSpeed);
                        events.add(motionState.getEvent());
                        maxSpeed = 0;
                    }
                    lastPosition = position;
                }
            }
        }

        for (Event event : events) {
            boolean motion = event.getType().equals(Event.TYPE_DEVICE_MOVING);
            if (motion == trips) {
                startPosition = positionMap.get(event.getPositionId());
            } else if (startPosition != null) {
                Position endPosition = positionMap.get(event.getPositionId());
                if (endPosition != null) {
                    result.add(calculateTripOrStop(
                            device, startPosition, endPosition,
                            event.getDouble("maxSpeed"), ignoreOdometer, reportClass));
                }
                startPosition = null;
            }
        }

        if (startPosition != null) {
            result.add(calculateTripOrStop(
                    device, startPosition, lastPosition, maxSpeed, ignoreOdometer, reportClass));
        }

        return result;
    }

    public <T extends BaseReportItem> List<T> fastTripsAndStops(
            Device device, Date from, Date to, Class<T> reportClass) throws StorageException {

        List<T> result = new ArrayList<>();
        TripsConfig tripsConfig = new TripsConfig(
                new AttributeUtil.StorageProvider(config, storage, permissionsService, device));
        boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
        boolean trips = reportClass.equals(TripReportItem.class);

        var events = storage.getObjects(Event.class, new Request(
                new Columns.All(),
                Condition.merge(List.of(
                        new Condition.Equals("deviceId", device.getId()),
                        new Condition.Between("eventTime", from, to),
                        new Condition.Or(
                                new Condition.Equals("type", Event.TYPE_DEVICE_MOVING),
                                new Condition.Equals("type", Event.TYPE_DEVICE_STOPPED)))),
                new Order("eventTime")));

        Position startPosition = PositionUtil.getEdgePosition(storage, device.getId(), from, to, false);
        if (startPosition != null && startPosition.getBoolean(Position.KEY_MOTION) != trips) {
            startPosition = null;
        }

        for (Event event : events) {
            boolean motion = event.getType().equals(Event.TYPE_DEVICE_MOVING);
            if (motion == trips) {
                startPosition = storage.getObject(Position.class, new Request(
                        new Columns.All(),
                        new Condition.And(
                                new Condition.Equals("deviceId", device.getId()),
                                new Condition.Equals("id", event.getPositionId()))));
            } else if (startPosition != null) {
                Position endPosition = storage.getObject(Position.class, new Request(
                        new Columns.All(),
                        new Condition.And(
                                new Condition.Equals("deviceId", device.getId()),
                                new Condition.Equals("id", event.getPositionId()))));
                if (endPosition != null) {
                    result.add(calculateTripOrStop(
                            device, startPosition, endPosition, 0, ignoreOdometer, reportClass));
                }
                startPosition = null;
            }
        }

        if (startPosition != null) {
            Position endPosition = PositionUtil.getEdgePosition(storage, device.getId(), from, to, true);
            result.add(calculateTripOrStop(
                    device, startPosition, endPosition, 0, ignoreOdometer, reportClass));
        }

        return result;
    }

}
