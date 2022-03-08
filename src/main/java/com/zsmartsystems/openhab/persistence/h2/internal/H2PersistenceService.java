/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.zsmartsystems.openhab.persistence.h2.internal;

import java.io.File;
import java.nio.file.Paths;
import java.security.InvalidParameterException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.h2.Driver;
import org.openhab.core.OpenHAB;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.i18n.TranslationProvider;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.RewindFastforwardType;
import org.openhab.core.library.types.StringListType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.FilterCriteria.Ordering;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.ModifiablePersistenceService;
import org.openhab.core.persistence.PersistenceItemInfo;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.persistence.strategy.PersistenceStrategy;
import org.openhab.core.types.State;
import org.openhab.core.types.TypeParser;
import org.openhab.core.types.UnDefType;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a base class that could be used for a implementation of a H2 {@link PersistenceService}.
 *
 * @author Chris Jackson - Initial contribution
 */
@NonNullByDefault
@Component(service = { PersistenceService.class, QueryablePersistenceService.class })
public class H2PersistenceService implements ModifiablePersistenceService {
    private final static String DEFAULT_SERVICE_NAME = "H2 Embedded Database";

    private static class Column {
        public static final String TIME = "time";
        public static final String CLAZZ = "clazz";
        public static final Object VALUE = "value";
    }

    private static class Schema {
        public static final String ITEM = "OSH_ITEM";
        public static final String METAINFO = "OSH_ITEM";

    }

    private static class SqlType {
        public static final String DECIMAL = "DECIMAL";
        public static final String TIMESTAMP = "TIMESTAMP";
        public static final String TINYINT = "TINYINT";
        public static final String VARCHAR = "VARCHAR";
    }

    protected static class FilterWhere {
        public final boolean begin;
        public final boolean end;
        public String prepared;

        public FilterWhere(final FilterCriteria filter) {
            this.begin = filter.getBeginDate() != null;
            this.end = filter.getEndDate() != null;
            prepared = "";
        }
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final String h2Url = "jdbc:h2:file:";

    private final Map<String, List<Class<? extends State>>> stateClasses = new HashMap<>();
    private final List<String> itemCache = new ArrayList<>();
    protected final ItemRegistry itemRegistry;
    protected final TimeZoneProvider timeZoneProvider;

    //private MessageBufferPacker messageBufferPacker = MessagePack.newDefaultBufferPacker();

    @Nullable
    private TranslationProvider i18nProvider;

    @Nullable
    protected Connection connection;

    @Nullable
    private BundleContext bundleContext;


    @Activate
    public H2PersistenceService(@Reference ItemRegistry itemRegistry, @Reference TimeZoneProvider timeZoneProvider) {
        this.itemRegistry = itemRegistry;
        this.timeZoneProvider = timeZoneProvider;

        // Ensure that known types are accessible by the classloader
        addStateClass(DateTimeType.class);
        addStateClass(DecimalType.class);
        addStateClass(HSBType.class);
        addStateClass(OnOffType.class);
        addStateClass(OpenClosedType.class);
        addStateClass(PlayPauseType.class);
        addStateClass(PointType.class);
        addStateClass(RawType.class);
        addStateClass(RewindFastforwardType.class);
        addStateClass(StringListType.class);
        addStateClass(StringType.class);
        addStateClass(UnDefType.class);
        addStateClass(UpDownType.class);
    }

    private void addStateClass(final Class<? extends State> stateClass) {
        List<Class<? extends State>> list = new ArrayList<>();
        list.add(stateClass);
        stateClasses.put(getStateClassKey(stateClass), list);
    }

    private String getStateClassKey(final Class<? extends State> stateClass) {
        return stateClass.getSimpleName();
    }

    protected void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    protected void deactivate() {
        disconnectFromDatabase();
        this.bundleContext = null;
    }

    @Reference
    public void setI18nProvider(TranslationProvider i18nProvider) {
        this.i18nProvider = i18nProvider;
    }

    public void unsetI18nProvider(TranslationProvider i18nProvider) {
        this.i18nProvider = null;
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        if (i18nProvider == null || locale == null) {
            return DEFAULT_SERVICE_NAME;
        }
        String name = i18nProvider.getText(bundleContext.getBundle(), String.format("%s.label", getId()),
                "H2 Embedded Database", locale);
        return name == null ? DEFAULT_SERVICE_NAME : name;
    }

    @Override
    public void store(Item item, @Nullable String alias) {
        store(item);
    }

    @Override
    public void store(Item item) {
        store(item, new Date(), getStateForItem(item));
    }

    @Override
    public void store(Item item, Date date, State state) {
        // Connect to H2 server if we're not already connected
        if (!connectToDatabase()) {
            logger.warn("{}: No connection to database. Can not persist item '{}'", getId(), item.getName());
            return;
        }

        final String tableName = getTableName(item.getName());

        if (!itemCache.contains(item.getName())) {
            itemCache.add(item.getName());
            if (createTable(state.getClass(), tableName)) {
            } else {
                logger.error("{}: Could not create table for item '{}'", getId(), item.getName());
                return;
            }
        }

        // Firstly, try an INSERT. This will work 99.9% of the time
        if (!insert(tableName, date, state)) {
            // The INSERT failed. This might be because we tried persisting data too quickly, or it might be
            // because we really want to UPDATE the data.
            // So, let's try an update. If the reason for the exception isn't due to the primary key already
            // existing, then we'll throw another exception.
            // Note that H2 stores times using the Java Date class, so resolution is milliseconds. We really
            // shouldn't be persisting data that quickly!
            if (!update(tableName, date, state)) {
                logger.error("{}: Could not store item '{}' in database.", getId(), item.getName());
                return;
            }
        }
        logger.debug("{}: Stored item '{}' state '{}'", getId(), item.getName(), state);
    }

    @Override
    public Set<PersistenceItemInfo> getItemInfo() {
        // Connect to H2 server if we're not already connected
        if (!connectToDatabase()) {
            logger.warn("{}: No connection to database.", getId());
            return Collections.emptySet();
        }

        // Retrieve the table array
        try (final Statement st = connection.createStatement()) {
            final String queryString = String.format(
                    "SELECT TABLE_NAME, ROW_COUNT_ESTIMATE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='%s';",
                    Schema.ITEM);

            // Turn use of the cursor on.
            st.setFetchSize(50);

            try (final ResultSet rs = st.executeQuery(queryString)) {
                // TODO: This won't work with binary serialisation!
                Set<PersistenceItemInfo> items = new HashSet<PersistenceItemInfo>();
                while (rs.next()) {
                    try (final Statement stTimes = connection.createStatement()) {
                        final String minMax = String.format("SELECT MIN(%s), MAX(%s) FROM %s", Column.TIME, Column.TIME,
                                getTableName(rs.getString(1)));
                        try (final ResultSet rsTimes = stTimes.executeQuery(minMax)) {

                            final Date earliest;
                            final Date latest;
                            if (rsTimes.next()) {
                                earliest = rsTimes.getTimestamp(1);
                                latest = rsTimes.getTimestamp(2);
                            } else {
                                earliest = null;
                                latest = null;
                            }
                            final H2PersistenceItem item = new H2PersistenceItem(rs.getString(1), rs.getInt(2),
                                    earliest, latest);
                            items.add(item);
                        }
                    }
                }
                return items;
            }

        } catch (final SQLException ex) {
            logger.error("{}: Error running query", getId(), ex);
            return Collections.emptySet();
        }
    }

    @Override
    public boolean remove(FilterCriteria filter) throws InvalidParameterException {
        // Connect to H2 server if we're not already connected
        if (!connectToDatabase()) {
            logger.warn("{}: No connection to database.", getId());
            return false;
        }

        if (filter == null || filter.getItemName() == null) {
            throw new InvalidParameterException(
                    "Invalid filter. Filter must be specified and item name must be supplied.");
        }

        final FilterWhere filterWhere = getFilterWhere(filter);

        final String queryString = String.format("DELETE FROM %s%s;", getTableName(filter.getItemName()),
                filterWhere.prepared);

        // Retrieve the table array
        try (final PreparedStatement st = connection.prepareStatement(queryString)) {
            int i = 0;
            if (filterWhere.begin) {
                st.setTimestamp(++i,
                        new Timestamp(filter.getBeginDate().getLong(ChronoField.INSTANT_SECONDS) * 1000));
            }
            if (filterWhere.end) {
                st.setTimestamp(++i,
                        new Timestamp(filter.getEndDate().getLong(ChronoField.INSTANT_SECONDS) * 1000));
            }

            st.execute(queryString);
            // final int rowsDeleted = st.getUpdateCount();

            // Do some housekeeping...
            // See how many rows remain - if it's 0, we should remove the table from the database
            try (final ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + getTableName(filter.getItemName()))) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    final String drop = "DROP TABLE " + getTableName(filter.getItemName());
                    st.execute(drop);
                }

                return true;
            }
        } catch (final SQLException ex) {
            logger.error("{}: Error running query", getId(), ex);
            return false;
        }
    }

    /**
     * Checks if we have a database connection
     *
     * @return true if connection has been established, false otherwise
     */
    private boolean isConnected() {
        // Check if connection is valid
        try {
            if (connection != null && !connection.isValid(5000)) {
                logger.error("{}: Connection is not valid!", getId());
            }
        } catch (final SQLException ex) {
            logger.error("{}: Error while checking connection", getId(), ex);
        }
        return connection != null;
    }

    /**
     * Connects to the database
     */
    protected boolean connectToDatabase() {
        // First, check if we're connected
        if (isConnected()) {
            return true;
        }

        // We're not connected, so connect
        try {
            // force loading of driver class into our classloader!
            Driver driver = new Driver();
            logger.info("{}: Connecting to database", getId());

            final String folderName = Paths.get(OpenHAB.getUserDataFolder(), getId()).toString();

            // Create path for serialization.
            final File folder = new File(folderName);
            if (!folder.exists() && !folder.mkdirs() && !folder.exists()) {
                logger.error("{}: Cannot create directory.", getId());
                return false;
            }

            final String databaseFileName = Paths.get(folderName, "smarthome").toString();

            String url = h2Url + databaseFileName;

            // Disable logging and defrag on shutdown
            url += ";AUTO_RECONNECT=TRUE;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0;DEFRAG_ALWAYS=true;FILE_LOCK=SOCKET";
            connection = DriverManager.getConnection(url);

            logger.info("{}: Connected to H2 database {}", getId(), databaseFileName);

            try (final Statement statement = connection.createStatement()) {
                for (final String schema : new String[] { Schema.ITEM, Schema.METAINFO }) {
                    statement.execute(String.format("CREATE SCHEMA IF NOT EXISTS %s;", schema));
                }
                // statement.executeUpdate(String.format("SET SCHEMA %s;", Schema.ITEM));
            }
        } catch (final RuntimeException | SQLException ex) {
            logger.error("{}: Failed connecting to the database", getId(), ex);
        }

        return isConnected();
    }

    /**
     * Disconnects from the database
     */
    private void disconnectFromDatabase() {
        logger.debug("{}: Disconnecting from database.", getId());
        if (connection != null) {
            try {
                connection.close();
                logger.debug("{}: Disconnected from database.", getId());
            } catch (final SQLException ex) {
                logger.error("{}: Failed disconnecting from the database", getId(), ex);
            }
            connection = null;
        }
    }

    protected String getTableName(String itemName) {
        return String.format("%s.\"%s\"", Schema.ITEM, itemName);
    }

    protected FilterWhere getFilterWhere(final FilterCriteria filter) {
        final FilterWhere filterWhere = new FilterWhere(filter);

        if (filterWhere.begin) {
            if (filterWhere.prepared.isEmpty()) {
                filterWhere.prepared += " WHERE";
            } else {
                filterWhere.prepared += " AND";
            }
            filterWhere.prepared += String.format(" %s >= ?", Column.TIME);
        }
        if (filterWhere.end) {
            if (filterWhere.prepared.isEmpty()) {
                filterWhere.prepared += " WHERE";
            } else {
                filterWhere.prepared += " AND";
            }
            filterWhere.prepared += String.format(" %s <= ?", Column.TIME);
        }
        return filterWhere;
    }

    protected String getFilterStringOrder(FilterCriteria filter) {
        if (filter.getOrdering() == Ordering.ASCENDING) {
            return String.format(" ORDER BY %s ASC", Column.TIME);
        } else {
            return String.format(" ORDER BY %s DESC", Column.TIME);
        }
    }

    protected String getFilterStringLimit(FilterCriteria filter) {
        if (filter.getPageSize() != 0x7fffffff) {
            return " LIMIT " + filter.getPageSize() + " OFFSET " + (filter.getPageNumber() * filter.getPageSize());
        } else {
            return "";
        }
    }

    @Override
    public String getId() {
        return "H2";
    }

    @Override
    public Iterable<HistoricItem> query(FilterCriteria filter) {
        // Connect to H2 server if we're not already connected
        if (!connectToDatabase()) {
            logger.warn("{}: Query aborted on item {} - H2 not connected!", getId(), filter.getItemName());
            return Collections.emptyList();
        }

        // Get the item name from the filter
        final String itemName = filter.getItemName();

        final FilterWhere filterWhere = getFilterWhere(filter);

        final String queryString = String.format("SELECT %s, %s, %s FROM %s%s%s%s", Column.TIME, Column.CLAZZ,
                Column.VALUE, getTableName(filter.getItemName()), filterWhere.prepared, getFilterStringOrder(filter),
                getFilterStringLimit(filter));

        try (final PreparedStatement st = connection.prepareStatement(queryString)) {
            int i = 0;
            if (filterWhere.begin) {
                st.setTimestamp(++i, Timestamp.valueOf(filter.getBeginDate().toLocalDateTime()));
            }
            if (filterWhere.end) {
                st.setTimestamp(++i, Timestamp.valueOf(filter.getEndDate().toLocalDateTime()));
            }

            // Turn use of the cursor on.
            st.setFetchSize(50);

            try (final ResultSet rs = st.executeQuery()) {
                final List<HistoricItem> items = new ArrayList<>();
                while (rs.next()) {
                    final Date time;
                    final String clazz;
                    final String value;

                    i = 0;
                    time = rs.getTimestamp(++i);
                    clazz = rs.getString(++i);
                    value = rs.getString(++i);
                    logger.trace("{}: itemName: {}, time: {}, clazz: {}, value: {}", getId(), itemName, time, clazz,
                            value);

                    final State state;
                    if (!stateClasses.containsKey(clazz)) {
                        if (itemRegistry != null) {
                            try {
                                final Item item = itemRegistry.getItem(itemName);
                                if (item != null) {
                                    for (final Class<? extends State> it : item.getAcceptedDataTypes()) {
                                        final String key = getStateClassKey(it);
                                        if (!stateClasses.containsKey(key)) {
                                            addStateClass(it);
                                            logger.warn("{}: Add new state class '{}'", getId(), clazz);
                                        }
                                    }
                                }
                            } catch (final ItemNotFoundException ex) {
                                logger.warn("{}: Cannot lookup state class because item '{}' is not known.", getId(),
                                        itemName, ex);
                                continue;
                            }
                        }
                    }

                    if (stateClasses.containsKey(clazz)) {
                        state = TypeParser.parseState(stateClasses.get(clazz), value);
                    } else {
                        logger.warn("{}: Unknown state class '{}'", getId(), clazz);
                        continue;
                    }

                    final H2HistoricItem sqlItem = new H2HistoricItem(itemName, state, time.toInstant()
                        .atZone(timeZoneProvider.getTimeZone()));
                    items.add(sqlItem);
                }
                return items;
            }

        } catch (final SQLException ex) {
            logger.error("{}: Error running query", getId(), ex);
            return Collections.emptyList();
        }
    }

    private State getStateForItem(final Item item) {
        return item.getState();
    }

    private boolean createTable(Class<? extends State> stateClass, final String tableName) {
        final String sql = String.format("CREATE TABLE IF NOT EXISTS %s (%s %s, %s %s,  %s %s, PRIMARY KEY(%s));",
                tableName, Column.TIME, SqlType.TIMESTAMP, Column.CLAZZ, SqlType.VARCHAR, Column.VALUE, SqlType.VARCHAR,
                Column.TIME);
        try (final Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            return true;
        } catch (final SQLException ex) {
            logger.error("{}: create table failed; statement '{}'", getId(), sql, ex);
            return false;
        }
    }

    private boolean insert(final String tableName, final Date date, final State state) {
        final String sql = String.format("INSERT INTO %s (%s, %s, %s) VALUES(?,?,?);", tableName, Column.TIME,
                Column.CLAZZ, Column.VALUE);

        try (final PreparedStatement stmt = connection.prepareStatement(sql)) {
//           MessageBuffer buffer= messageBufferPacker.toMessageBuffer();
//            byte[] dest = new byte[128];
//            int length = writer.writeCyclic(state, 1, dest, 0);
//            InputStream dataStream = new ByteArrayInputStream(Arrays.copyOfRange(dest, 0, length));

            int i = 0;
            stmt.setTimestamp(++i, new Timestamp(date.getTime()));
            stmt.setString(++i, getStateClassKey(state.getClass()));
             stmt.setString(++i, state.toString());
//            stmt.setBinaryStream(++i, dataStream);
            stmt.executeUpdate();
            return true;
        } catch (final SQLException ex) {
            logger.warn("{}: insert failed; statement '{}'", getId(), sql, ex);
            return false;
        }
    }

    public boolean update(final String tableName, final Date date, final State state) {
        final String sql = String.format("UPDATE %s SET %s = ?, %s = ? WHERE TIME = ?", tableName, Column.CLAZZ,
                Column.VALUE);
        try (final PreparedStatement stmt = connection.prepareStatement(sql)) {
            int i = 0;
            stmt.setString(++i, getStateClassKey(state.getClass()));
            stmt.setString(++i, state.toString());
//            stmt.setBinaryStream(++i, null);
            stmt.setTimestamp(++i, new Timestamp(date.getTime()));
            stmt.executeUpdate();
            return true;
        } catch (final SQLException ex) {
            logger.trace("{}: update failed; statement '{}'", getId(), sql, ex);
            return false;
        }
    }

    @Override
    public List<PersistenceStrategy> getDefaultStrategies() {
        return Collections.emptyList();
    }
}
