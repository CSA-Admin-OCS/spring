package com.open.spring.mvc.backups;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * Consolidated controller for all import operations.
 * Handles both full database imports and specific API endpoint imports.
 */
@Component
@Controller
@RequestMapping("/api/imports")
public class ImportsController {

    // ========== FULL DATABASE IMPORT CONFIGURATION ==========
    private static final String BACKUP_DIR = "./volumes/backups/";
    private static final String LOG_FILE_PATH = "./volumes/logs/restore_operations.log";

    // ========== SPECIFIC ENDPOINT IMPORT CONFIGURATION ==========
    @Value("${backup.base.path:./backups}")
    private String backupBasePath;

    @Value("${server.port:8585}")
    private String serverPort;

    /** false in schema-tool / test mode: no startup pragmas or integrity check. */
    @Value("${app.bootstrap.enabled:true}")
    private boolean bootstrapEnabled;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final Object lock = new Object();

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    // Configuration for import endpoints and their corresponding directories
    private final List<ImportEndpoint> endpoints = Arrays.asList(
        new ImportEndpoint("/api/people/bulk/import", "person"),
        new ImportEndpoint("/api/groups/bulk/create", "groups"),
        new ImportEndpoint("/api/tinkle/bulk/create", "tinkle"),
        new ImportEndpoint("/api/calendar/add_bulk", "calendar"),
        new ImportEndpoint("/bank/bulk/create", "bank")
    );

    // ========== INITIALIZATION ==========

    /**
     * Initialize database on application startup
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void initializeOnStartup() {
        if (!bootstrapEnabled) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (isSqliteDatabase(connection)) {
                // Enable WAL mode for better concurrency
                enableWalMode(connection);

                // Set a busy timeout to wait for locks
                setBusyTimeout(connection, 30000); // 30 seconds

                // Check SQLite version for debugging
                checkSqliteVersion(connection);

                // Verify database integrity
                verifyDatabaseIntegrity(connection);

                System.out.println("Database initialized successfully in WAL mode.");
            } else {
                System.out.println("Database initialized successfully for non-SQLite datasource.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            System.err.println("Failed to initialize database: " + e.getMessage());
        }
    }

    // ========== FULL DATABASE IMPORT ENDPOINTS ==========

    /**
     * Manual import endpoint for full database restoration
     */
    @PostMapping("/manual")
    public String manualImport(@RequestParam("file") MultipartFile file, Model model) {
        synchronized (lock) {
            if (file.isEmpty()) {
                model.addAttribute("message", "No file uploaded.");
                return "db_management/db_error";
            }

            try {
                String result = importFromMultipartFile(file);
                manageBackups();
                model.addAttribute("message", result);
                return "db_management/db_success";
            } catch (Exception e) {
                e.printStackTrace();
                model.addAttribute("message", "Failed to process the uploaded file: " + e.getMessage());
                return "db_management/db_error";
            }
        }
    }

    /**
     * Get list of available backups for full database
     */
    @GetMapping("/backups")
    public String getBackupsList(Model model) {
        try {
            List<BackupFileInfo> backups = getAllBackupFiles();
            model.addAttribute("backups", backups);
            return "db_management/backups";
        } catch (Exception e) {
            model.addAttribute("message", "Error fetching backups: " + e.getMessage());
            return "db_management/db_error";
        }
    }

    /**
     * Revert to a specific full database backup
     */
    @PostMapping("/revert")
    public String revertToBackup(@RequestParam("filename") String filename, Model model) {
        synchronized (lock) {
            try {
                // Prevent path traversal attacks
                Path baseDir = Paths.get(BACKUP_DIR).toAbsolutePath().normalize();
                Path filePath = baseDir.resolve(filename).normalize();
                if (!filePath.startsWith(baseDir)) {
                    model.addAttribute("message", "Invalid filename path.");
                    return "db_management/db_error";
                }
                
                File backupFile = filePath.toFile();
                if (!backupFile.exists()) {
                    model.addAttribute("message", "Backup file not found.");
                    return "db_management/db_error";
                }
                
                // Log the restore operation
                logRestoreOperation(filename);
                
                String result = importFromFile(backupFile);
                model.addAttribute("message", result);
                return "db_management/db_success";
            } catch (Exception e) {
                e.printStackTrace();
                // Log the error too
                logRestoreOperation(filename + " (FAILED: " + e.getMessage() + ")");
                model.addAttribute("message", "Failed to revert to backup: " + e.getMessage());
                return "db_management/db_error";
            }
        }
    }

    /**
     * View details of a specific full database backup
     */
    @GetMapping("/view")
    public String viewBackupDetails(@RequestParam("filename") String filename, Model model) {
        try {
            // Prevent path traversal attacks
            Path baseDir = Paths.get(BACKUP_DIR).toAbsolutePath().normalize();
            Path filePath = baseDir.resolve(filename).normalize();
            if (!filePath.startsWith(baseDir)) {
                model.addAttribute("message", "Invalid filename path.");
                return "db_management/db_error";
            }
            
            File backupFile = filePath.toFile();
            if (!backupFile.exists()) {
                model.addAttribute("message", "Backup file not found.");
                return "db_management/db_error";
            }
            
            String rawJson = new String(Files.readAllBytes(backupFile.toPath()));
            BackupData backupData = objectMapper.readValue(rawJson, BackupData.class);
            
            model.addAttribute("filename", filename);
            model.addAttribute("tables", backupData.getTables());
            model.addAttribute("creationDate", new java.util.Date(backupFile.lastModified()));
            model.addAttribute("fileSize", backupFile.length() / 1024); // Size in KB
            
            return "db_management/backup-details";
        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("message", "Failed to read backup: " + e.getMessage());
            return "db_management/db_error";
        }
    }

    /**
     * View restore operation logs
     */
    @GetMapping("/logs")
    public String viewRestoreLogs(Model model) {
        try {
            List<String> logs = readLogFile();
            model.addAttribute("logs", logs);
            return "db_management/restore_logs";
        } catch (IOException e) {
            model.addAttribute("message", "Failed to read log file: " + e.getMessage());
            return "db_management/db_error";
        }
    }

    // ========== SPECIFIC ENDPOINT IMPORT ENDPOINTS ==========

    /**
     * Import data from the most recent backup file for all endpoints
     */
    @PostMapping("/specific/import-all-latest")
    public ResponseEntity<Map<String, Object>> importAllFromLatestBackups() {
        System.out.println("Starting import process from latest backups...");
        
        Map<String, Object> results = new HashMap<>();
        List<String> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        
        for (ImportEndpoint endpoint : endpoints) {
            try {
                importFromLatestBackup(endpoint);
                successes.add(endpoint.getDirectoryName());
            } catch (Exception e) {
                System.err.println("Failed to import to endpoint " + endpoint.getPath() + ": " + e.getMessage());
                failures.add(endpoint.getDirectoryName() + ": " + e.getMessage());
            }
        }
        
        results.put("successes", successes);
        results.put("failures", failures);
        results.put("message", "Import process completed");
        
        System.out.println("Import process completed.");
        return ResponseEntity.ok(results);
    }

    /**
     * Import data from a specific backup file
     */
    @PostMapping("/specific/import-specific")
    public ResponseEntity<Map<String, Object>> importFromSpecificFile(
            @RequestParam String directoryName, 
            @RequestParam String filename) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            ImportEndpoint endpoint = findEndpointByDirectory(directoryName);
            if (endpoint == null) {
                result.put("error", "No endpoint configured for directory: " + directoryName);
                return ResponseEntity.badRequest().body(result);
            }

            // Prevent path traversal attacks
            Path baseDir = Paths.get(backupBasePath, directoryName).toAbsolutePath().normalize();
            Path filePath = baseDir.resolve(filename).normalize();
            if (!filePath.startsWith(baseDir)) {
                result.put("error", "Invalid filename path.");
                return ResponseEntity.badRequest().body(result);
            }
            
            // Enforce expected file format for specific imports
            String nameOnly = filePath.getFileName().toString();
            if (!nameOnly.endsWith(".json") || !nameOnly.startsWith(directoryName + "_backup_")) {
                result.put("error", "Invalid backup file format.");
                return ResponseEntity.badRequest().body(result);
            }
            
            if (!Files.exists(filePath)) {
                result.put("error", "Backup file not found: " + filePath);
                return ResponseEntity.badRequest().body(result);
            }

            importFromSpecificFile(endpoint, filePath);
            result.put("success", true);
            result.put("message", "Successfully imported from " + filename);
            result.put("directory", directoryName);
            result.put("filename", filename);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            result.put("error", "Import failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /**
     * List available backup files for a specific directory
     */
    @GetMapping("/specific/list-backups/{directoryName}")
    public ResponseEntity<Map<String, Object>> listBackupFiles(@PathVariable String directoryName) {
        try {
            List<SpecificBackupFileInfo> backupFiles = getBackupFilesWithInfo(directoryName);
            
            Map<String, Object> result = new HashMap<>();
            result.put("directory", directoryName);
            result.put("files", backupFiles);
            result.put("count", backupFiles.size());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", "Failed to list backup files: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /**
     * List all available backup directories and their file counts
     */
    @GetMapping("/specific/list-all-backups")
    public ResponseEntity<Map<String, Object>> listAllBackups() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> directories = new HashMap<>();
        
        for (ImportEndpoint endpoint : endpoints) {
            try {
                List<SpecificBackupFileInfo> files = getBackupFilesWithInfo(endpoint.getDirectoryName());
                Map<String, Object> dirInfo = new HashMap<>();
                dirInfo.put("fileCount", files.size());
                dirInfo.put("endpoint", endpoint.getPath());
                dirInfo.put("files", files);
                directories.put(endpoint.getDirectoryName(), dirInfo);
            } catch (Exception e) {
                Map<String, Object> dirInfo = new HashMap<>();
                dirInfo.put("error", e.getMessage());
                dirInfo.put("endpoint", endpoint.getPath());
                directories.put(endpoint.getDirectoryName(), dirInfo);
            }
        }
        
        result.put("directories", directories);
        result.put("totalDirectories", endpoints.size());
        
        return ResponseEntity.ok(result);
    }

    /**
     * Get all configured import endpoints
     */
    @GetMapping("/specific/endpoints")
    public ResponseEntity<List<ImportEndpoint>> getConfiguredEndpoints() {
        return ResponseEntity.ok(endpoints);
    }

    /**
     * Validate that all import endpoints are accessible
     */
    @GetMapping("/specific/validate-endpoints")
    public ResponseEntity<Map<String, Object>> validateEndpoints() {
        System.out.println("Validating import endpoints...");
        
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> endpointStatuses = new ArrayList<>();
        
        for (ImportEndpoint endpoint : endpoints) {
            String url = "http://localhost:" + serverPort + endpoint.getPath();
            Map<String, Object> status = new HashMap<>();
            status.put("endpoint", endpoint.getPath());
            status.put("directory", endpoint.getDirectoryName());
            
            try {
                // Try to make a HEAD request to check if endpoint exists
                HttpHeaders headers = new HttpHeaders();
                headers.add("X-Validation", "endpoint-check");
                HttpEntity<String> entity = new HttpEntity<>(headers);
                
                ResponseEntity<String> response = restTemplate.exchange(
                    url, 
                    HttpMethod.HEAD, 
                    entity, 
                    String.class
                );
                
                status.put("accessible", true);
                status.put("statusCode", response.getStatusCodeValue());
                System.out.println("✓ Endpoint " + endpoint.getPath() + " is accessible");
                
            } catch (Exception e) {
                status.put("accessible", false);
                status.put("error", e.getMessage());
                System.err.println("✗ Endpoint " + endpoint.getPath() + " is not accessible: " + e.getMessage());
            }
            
            endpointStatuses.add(status);
        }
        
        result.put("endpoints", endpointStatuses);
        result.put("totalEndpoints", endpoints.size());
        
        return ResponseEntity.ok(result);
    }

    /**
     * UI page for backup management
     */
    @GetMapping("/management")
    public String backupManagementPage() {
        return "db_management/specific_backups";
    }

    // ========== FULL DATABASE IMPORT HELPER METHODS ==========

    /**
     * Import data from a MultipartFile
     */
    public String importFromMultipartFile(MultipartFile file) {
        try {
            InputStream inputStream = file.getInputStream();
            String rawJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    
            // Parse the JSON data first
            Map<String, List<Map<String, Object>>> data = objectMapper.readValue(rawJson, Map.class);
            
            // FIRST PASS: Create all tables that don't exist yet
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false); // Start transaction
                
                // Get existing tables
                Set<String> existingTables = getAllTables(connection);
                
                // Create all tables first before attempting to insert any data
                for (Map.Entry<String, List<Map<String, Object>>> entry : data.entrySet()) {
                    String tableName = sanitizeTableName(entry.getKey());
                    List<Map<String, Object>> tableData = entry.getValue();
                    
                    if (!tableName.endsWith("_seq") && !tableData.isEmpty() && !existingTables.contains(tableName)) {
                        System.out.println("Skipping table " + tableName + ": not in the schema "
                                + "(restore never creates tables; run ./dev.sh migrate spring upgrade)");
                    }
                }
                
                connection.commit();
            }
            
            // SECOND PASS: Process all the data normally
            sanitizeAndProcessData(data, true);
            
            return "Data imported successfully from uploaded file: " + file.getOriginalFilename();
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to import data: " + e.getMessage();
        }
    }

    /**
     * Import from a File object
     */
    private String importFromFile(File jsonFile) {
        int retries = 3;
        while (retries > 0) {
            try {
                // First establish a connection and set WAL mode BEFORE starting any transaction
                try (Connection setupConnection = dataSource.getConnection()) {
                    if (isSqliteDatabase(setupConnection)) {
                        enableWalMode(setupConnection);
                        setBusyTimeout(setupConnection, 30000);
                        checkSqliteVersion(setupConnection);
                        verifyDatabaseIntegrity(setupConnection);
                    }
                }
                
                // Now create a new connection for the transaction
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    
                    // Read and parse JSON file
                    String rawJson = new String(Files.readAllBytes(jsonFile.toPath()));
                    
                    objectMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
                    objectMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                    
                    BackupData backupData = objectMapper.readValue(rawJson, BackupData.class);
                    Map<String, List<Map<String, Object>>> data = backupData.getTables();
                    
                    sanitizeAndProcessData(data, true);
                    
                    connection.commit();
                    return "Data imported successfully from JSON file: " + jsonFile.getAbsolutePath();
                }
            } catch (SQLException e) {
                if (isRetryableLockException(e)) {
                    retries--;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "Thread interrupted while waiting to retry.";
                    }
                } else {
                    e.printStackTrace();
                    return "Failed to import data: " + e.getMessage();
                }
            } catch (Exception e) {
                e.printStackTrace();
                return "Failed to import data: " + e.getMessage();
            }
        }
        return "Failed to acquire database lock after retries.";
    }

    /**
     * Sanitize and process the data for full database import
     */
    private void sanitizeAndProcessData(Map<String, List<Map<String, Object>>> data, boolean removeExcessData) throws SQLException {
        try (Connection setupConnection = dataSource.getConnection()) {
            if (isSqliteDatabase(setupConnection)) {
                enableWalMode(setupConnection);
                setBusyTimeout(setupConnection, 30000);
            }
        }
        
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean constraintsDisabled = false;

            try {
                disableReferentialIntegrity(connection);
                constraintsDisabled = true;

                Set<String> allTables = getAllTables(connection);
                Set<String> tablesInJson = new HashSet<>(data.keySet());

                // Update sequence tables first
                updateSequenceTables(connection, data);

                // Insert data into other tables
                for (Map.Entry<String, List<Map<String, Object>>> entry : data.entrySet()) {
                    String tableName = sanitizeTableName(entry.getKey());
                    List<Map<String, Object>> tableData = entry.getValue();

                    if (!tableName.endsWith("_seq")) {
                        try {
                            ensureTableExists(connection, tableName, tableData, removeExcessData);

                            if (removeExcessData) {
                                clearTableData(connection, tableName);
                            }

                            if (!tableData.isEmpty()) {
                                insertTableData(connection, tableName, tableData);
                            }
                        } catch (SQLException e) {
                            System.err.println("Error processing table " + tableName + ": " + e.getMessage());
                            throw e;
                        }
                    }
                }

                if (removeExcessData) {
                    System.out.println("Preserving all existing tables not in import data");

                    for (String tableName : allTables) {
                        if (!isSystemTable(tableName) &&
                            !tablesInJson.contains(tableName) &&
                            !tableName.endsWith("_seq")) {
                            System.out.println("Preserving existing table not in import: " + tableName);
                        }
                    }
                }

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                if (constraintsDisabled) {
                    restoreReferentialIntegrity(connection);
                }
            }
        }
    }

    // ========== SPECIFIC ENDPOINT IMPORT HELPER METHODS ==========

    /**
     * Get backup files with info for specific directory
     */
    private List<SpecificBackupFileInfo> getBackupFilesWithInfo(String directoryName) throws IOException {
        Path backupDir = Paths.get(backupBasePath, directoryName);
        
        if (!Files.exists(backupDir)) {
            throw new IllegalArgumentException("Backup directory not found: " + backupDir);
        }

        File[] files = backupDir.toFile().listFiles((dir, name) -> 
            name.startsWith(directoryName + "_backup_") && name.endsWith(".json"));
        
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        
        return Arrays.stream(files)
                .map(file -> new SpecificBackupFileInfo(
                    file.getName(),
                    file.lastModified(),
                    file.length(),
                    directoryName
                ))
                .collect(Collectors.toList());
    }

    /**
     * Import from the latest backup for a specific endpoint
     */
    private void importFromLatestBackup(ImportEndpoint endpoint) throws IOException {
        Path backupDir = Paths.get(backupBasePath, endpoint.getDirectoryName());
        
        if (!Files.exists(backupDir)) {
            System.out.println("No backup directory found for " + endpoint.getDirectoryName() + ", skipping import");
            return;
        }

        File[] files = backupDir.toFile().listFiles((dir, name) -> 
            name.startsWith(endpoint.getDirectoryName() + "_backup_") && name.endsWith(".json"));
        
        if (files == null || files.length == 0) {
            System.out.println("No backup files found for " + endpoint.getDirectoryName() + ", skipping import");
            return;
        }

        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        
        Path latestFile = files[0].toPath();
        importFromSpecificFile(endpoint, latestFile);
    }

    /**
     * Import from a specific file path for a specific endpoint
     */
    private void importFromSpecificFile(ImportEndpoint endpoint, Path filePath) throws IOException {
        String url = "http://localhost:" + serverPort + endpoint.getPath();
        
        try {
            System.out.println("Reading backup file: " + filePath.toString());
            String jsonContent = Files.readString(filePath);
            
            if (jsonContent == null || jsonContent.trim().isEmpty()) {
                System.out.println("Empty backup file " + filePath + ", skipping import");
                return;
            }

            // Validate JSON
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            
            // Prepare HTTP headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("X-Import-Source", "backup-restore");
            headers.add("X-Import-Timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));
            
            // Create HTTP entity
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonContent, headers);
            
            // Make API call
            System.out.println("Importing to API: " + url);
            ResponseEntity<String> response = restTemplate.exchange(
                url, 
                HttpMethod.POST, 
                requestEntity, 
                String.class
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Successfully imported data from " + filePath.getFileName() + 
                                 " to " + endpoint.getPath());
                System.out.println("Response: " + response.getBody());
            } else {
                System.err.println("Import failed with status: " + response.getStatusCode());
                System.err.println("Response: " + response.getBody());
            }
            
        } catch (Exception e) {
            System.err.println("Error during import from " + filePath + " to " + endpoint.getPath() + ": " + e.getMessage());
            throw new RuntimeException("Import failed for " + endpoint.getPath(), e);
        }
    }

    /**
     * Find endpoint by directory name
     */
    private ImportEndpoint findEndpointByDirectory(String directoryName) {
        return endpoints.stream()
                .filter(endpoint -> endpoint.getDirectoryName().equals(directoryName))
                .findFirst()
                .orElse(null);
    }

    private boolean isSqliteDatabase(Connection connection) throws SQLException {
        String url = connection.getMetaData().getURL();
        return url != null && url.startsWith("jdbc:sqlite:");
    }

    // ========== DATABASE UTILITY METHODS ==========

    private void enableWalMode(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
        }
    }

    private void setBusyTimeout(Connection connection, int timeoutMillis) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + timeoutMillis + ";");
        }
    }

    private void checkSqliteVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT sqlite_version();")) {
            if (resultSet.next()) {
                System.out.println("SQLite Version: " + resultSet.getString(1));
            }
        }
    }

    private void verifyDatabaseIntegrity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check;")) {
            while (resultSet.next()) {
                System.out.println("Integrity Check: " + resultSet.getString(1));
            }
        }
    }

    private Set<String> getAllTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData metaData = connection.getMetaData();
        
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (!isSystemTable(tableName)) {
                    tables.add(tableName);
                }
            }
        }
        
        return tables;
    }

    private void clearTableData(Connection connection, String tableName) throws SQLException {
        String sql = "DELETE FROM " + quoteIdentifier(connection, tableName);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("Cleared all data from table: " + tableName);
        }
    }

    private void updateSequenceTables(Connection connection, Map<String, List<Map<String, Object>>> data) throws SQLException {
        for (Map.Entry<String, List<Map<String, Object>>> entry : data.entrySet()) {
            String tableName = entry.getKey();
            if (tableName.endsWith("_seq")) {
                createSequenceTableIfNotExists(connection, tableName);

                List<Map<String, Object>> tableData = entry.getValue();
                if (!tableData.isEmpty()) {
                    Object nextValObj = tableData.get(0).get("next_val");
                    long nextValFromJson = 0;

                    if (nextValObj instanceof Number) {
                        nextValFromJson = ((Number) nextValObj).longValue();
                    } else {
                        throw new IllegalArgumentException("next_val must be a number (Integer or Long)");
                    }

                    updateSequenceValue(connection, tableName, nextValFromJson);
                }
            }
        }
    }

    private void createSequenceTableIfNotExists(Connection connection, String tableName) throws SQLException {
        if (!tableExists(connection, tableName)) {
            String quotedTableName = quoteIdentifier(connection, tableName);
            String sql = "CREATE TABLE " + quotedTableName + " (next_val BIGINT NOT NULL)";
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                String initSql = "INSERT INTO " + quotedTableName + " (next_val) VALUES (0)";
                statement.execute(initSql);
                System.out.println("Created table: " + tableName);
            }
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet resultSet = meta.getTables(connection.getCatalog(), connection.getSchema(), tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private void updateSequenceValue(Connection connection, String tableName, long nextValFromJson) throws SQLException {
        String sql = "UPDATE " + quoteIdentifier(connection, tableName) + " SET next_val = CASE WHEN next_val > ? THEN next_val ELSE ? END";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nextValFromJson);
            statement.setLong(2, nextValFromJson);
            statement.executeUpdate();
        }
    }

    private String sanitizeTableName(String tableName) {
        return tableName.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private void insertTableData(Connection connection, String tableName, List<Map<String, Object>> tableData) throws SQLException {
        if (tableData.isEmpty()) {
            System.out.println("No data to insert for table: " + tableName);
            return;
        }
    
        if (!tableExists(connection, tableName)) {
            System.out.println("Skipping table " + tableName + ": not in the schema "
                    + "(restore never creates tables; run ./dev.sh migrate spring upgrade)");
            return;
        }

        // Restore never alters the schema: load the columns both sides share and report the rest.
        Set<String> existingColumns = getExistingColumns(connection, tableName);
        Set<String> columns = new java.util.LinkedHashSet<>();
        for (String column : tableData.get(0).keySet()) {
            if (existingColumns.contains(column)) {
                columns.add(column);
            } else {
                System.out.println("Warning: column " + tableName + "." + column
                        + " is not in the schema; its values are not restored");
            }
        }
        if (columns.isEmpty()) {
            System.out.println("Skipping table " + tableName + ": no columns in common with the schema");
            return;
        }

        String sql = buildInsertQuery(connection, tableName, columns);
    
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            int batchSize = 0;
            for (Map<String, Object> row : tableData) {
                int index = 1;
                for (String column : columns) {
                    preparedStatement.setObject(index++, row.get(column));
                }
                preparedStatement.addBatch();
                batchSize++;
                
                if (batchSize >= 100) {
                    preparedStatement.executeBatch();
                    batchSize = 0;
                }
            }
    
            if (batchSize > 0) {
                preparedStatement.executeBatch();
            }
            
            System.out.println("Successfully inserted " + tableData.size() + " rows into " + tableName);
        } catch (SQLException e) {
            System.err.println("Error inserting data into " + tableName + ": " + e.getMessage());
            throw e;
        }
    }

    private String buildInsertQuery(Connection connection, String tableName, Set<String> columns) throws SQLException {
        StringBuilder columnList = new StringBuilder();
        StringBuilder valuePlaceholders = new StringBuilder();

        for (String column : columns) {
            if (columnList.length() > 0) {
                columnList.append(", ");
                valuePlaceholders.append(", ");
            }
            columnList.append(quoteIdentifier(connection, column));
            valuePlaceholders.append("?");
        }

        return "INSERT INTO " + quoteIdentifier(connection, tableName) + " (" + columnList + ") VALUES (" + valuePlaceholders + ")";
    }

    private Set<String> getExistingColumns(Connection connection, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), connection.getSchema(), tableName, null)) {
            
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        
        return columns;
    }

    private void ensureTableExists(Connection connection, String tableName, List<Map<String, Object>> tableData, boolean removeExcessData) throws SQLException {
        // Restore never creates tables or columns (schema is owned by Flyway); only report.
        if (!tableExists(connection, tableName)) {
            System.out.println("Table " + tableName + " is not in the schema; its rows are skipped "
                    + "(run ./dev.sh migrate spring upgrade if a migration should have created it)");
            return;
        }
        if (tableData.isEmpty()) {
            return;
        }
        Set<String> existingColumns = getExistingColumns(connection, tableName);
        for (String column : tableData.get(0).keySet()) {
            if (!existingColumns.contains(column)) {
                System.out.println("Warning: column " + tableName + "." + column
                        + " is not in the schema; its values are not restored");
            }
        }
    }

    private boolean isMySqlDatabase(Connection connection) throws SQLException {
        String url = connection.getMetaData().getURL();
        return url != null && url.startsWith("jdbc:mysql:");
    }

    private boolean isRetryableLockException(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("database is locked") ||
               message.contains("deadlock") ||
               message.contains("lock wait timeout") ||
               message.contains("could not obtain lock");
    }

    private void disableReferentialIntegrity(Connection connection) throws SQLException {
        if (isSqliteDatabase(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = OFF");
            }
        } else if (isMySqlDatabase(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
            }
        }
    }

    private void restoreReferentialIntegrity(Connection connection) throws SQLException {
        if (isSqliteDatabase(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        } else if (isMySqlDatabase(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        }
    }

    private String quoteIdentifier(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }

        String safeIdentifier = identifier.replace(quote, quote + quote);
        return quote + safeIdentifier + quote;
    }

    private boolean isSystemTable(String tableName) {
        return tableName.startsWith("sqlite_")
            || tableName.startsWith("mysql_")
            || tableName.startsWith("performance_schema")
            || tableName.startsWith("information_schema")
            || tableName.startsWith("sys");
    }

    private void manageBackups() {
        File backupsDir = new File(BACKUP_DIR);
        if (!backupsDir.exists() || !backupsDir.isDirectory()) {
            return;
        }

        File[] backupFiles = backupsDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".json"));

        if (backupFiles == null || backupFiles.length <= 3) {
            return;
        }

        Arrays.sort(backupFiles, Comparator.comparingLong(File::lastModified));

        for (int i = 0; i < backupFiles.length - 3; i++) {
            if (backupFiles[i].delete()) {
                System.out.println("Deleted old backup: " + backupFiles[i].getName());
            } else {
                System.out.println("Failed to delete old backup: " + backupFiles[i].getName());
            }
        }
    }

    private void logRestoreOperation(String filename) {
        try {
            File logDir = new File("./volumes/logs/");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            LocalDateTime now = LocalDateTime.now();
            String timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            Path logPath = Paths.get(LOG_FILE_PATH);
            String logEntry = timestamp + " - Restore operation performed: " + filename + "\n";
            
            Files.write(logPath, logEntry.getBytes(), 
                        java.nio.file.StandardOpenOption.CREATE, 
                        java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to log restore operation: " + e.getMessage());
        }
    }

    private List<String> readLogFile() throws IOException {
        List<String> logs = new ArrayList<>();
        File logFile = new File(LOG_FILE_PATH);
        
        if (!logFile.exists()) {
            return logs;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.add(line);
            }
        }
    
        Collections.reverse(logs);
        return logs;
    }

    private List<BackupFileInfo> getAllBackupFiles() {
        File backupsDir = new File(BACKUP_DIR);
        if (!backupsDir.exists() || !backupsDir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] backupFiles = backupsDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".json"));
        if (backupFiles == null || backupFiles.length == 0) {
            return Collections.emptyList();
        }

        return Arrays.stream(backupFiles)
            .map(file -> new BackupFileInfo(
                file.getName(),
                new java.util.Date(file.lastModified()),
                file.length() / 1024,
                getTableCountFromBackup(file)
            ))
            .sorted(Comparator.comparing(BackupFileInfo::getCreationDate).reversed())
            .collect(Collectors.toList());
    }

    private int getTableCountFromBackup(File file) {
        try {
            String rawJson = new String(Files.readAllBytes(file.toPath()));
            BackupData backupData = objectMapper.readValue(rawJson, BackupData.class);
            return backupData.getTables().size();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // ========== INNER CLASSES AND DTOS ==========

    public static class BackupData {
        private Map<String, List<Map<String, Object>>> tables = new HashMap<>();

        @JsonAnySetter
        public void set(String key, Object value) {
            if (value instanceof List) {
                tables.put(key, (List<Map<String, Object>>) value);
            } else {
                throw new IllegalArgumentException("Expected a List<Map<String, Object>> for key: " + key);
            }
        }

        public Map<String, List<Map<String, Object>>> getTables() {
            return tables;
        }

        public void setTables(Map<String, List<Map<String, Object>>> tables) {
            this.tables = tables;
        }
    }

    public static class BackupFileInfo {
        private final String filename;
        private final java.util.Date creationDate;
        private final long sizeKB;
        private final int tableCount;

        public BackupFileInfo(String filename, java.util.Date creationDate, long sizeKB, int tableCount) {
            this.filename = filename;
            this.creationDate = creationDate;
            this.sizeKB = sizeKB;
            this.tableCount = tableCount;
        }

        public String getFilename() {
            return filename;
        }

        public java.util.Date getCreationDate() {
            return creationDate;
        }

        public long getSizeKB() {
            return sizeKB;
        }

        public int getTableCount() {
            return tableCount;
        }
    }

    public static class ImportEndpoint {
        private final String path;
        private final String directoryName;

        public ImportEndpoint(String path, String directoryName) {
            this.path = path;
            this.directoryName = directoryName;
        }

        public String getPath() {
            return path;
        }

        public String getDirectoryName() {
            return directoryName;
        }

        @Override
        public String toString() {
            return "ImportEndpoint{" +
                    "path='" + path + '\'' +
                    ", directoryName='" + directoryName + '\'' +
                    '}';
        }
    }

    public static class SpecificBackupFileInfo {
        private String filename;
        private long lastModified;
        private long size;
        private String directory;
        
        public SpecificBackupFileInfo(String filename, long lastModified, long size, String directory) {
            this.filename = filename;
            this.lastModified = lastModified;
            this.size = size;
            this.directory = directory;
        }
        
        public String getFilename() { return filename; }
        public long getLastModified() { return lastModified; }
        public long getSize() { return size; }
        public String getDirectory() { return directory; }
    }
}