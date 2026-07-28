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

    // Positive jump in fuel level larger than this threshold between two
    // consecutive samples is treated as a refill event (segment boundary).
    private static final double FUEL_REFILL_THRESHOLD = 5.0;         // litres
    private static final double FUEL_LEVEL_REFILL_THRESHOLD = 5.0;   // percent of tank

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
        double total = 0;
        Double segmentStart = null;
        Double lastValid = null;
        for (Position curr : positions) {
            if (!curr.hasAttribute(key)) {
                continue;
            }
            double currValue = curr.getDouble(key);
            if (segmentStart == null) {
                segmentStart = currValue;
            } else if (lastValid != null && currValue - lastValid > refillThreshold) {
                // Refill detected between the previous valid sample and this one.
                total += segmentStart - lastValid;
                segmentStart = currValue;
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
        if (startPosition != null && !startPosition.getBoolean(Position.KEY_MOTION)) {
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
