package dev.masatolab.trashtotrack;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String[] GENRES = {
            "AUTO", "DUB", "AMBIENT", "LO-FI", "TECHNO", "HIP-HOP", "TRIP HOP",
            "JAZZ", "ELECTRONICA", "DRONE", "HOUSE", "BREAKBEAT", "CINEMATIC"
    };
    private static final String PRIVACY_PREFS = "privacy_settings";
    private static final String PREF_RICH_ESSENCE = "rich_essence_mode";
    private static final String OUTPUT_PREFS = "output_settings";
    private static final String PREF_OUTPUT_FOLDER_PATH = "output_folder_path";



    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<SelectedFile> allFiles = new ArrayList<>();
    private final List<SelectedFile> currentVisibleEntries = new ArrayList<>();
    private final Map<String, SelectedFile> selected = new HashMap<>();

    private String activeFilter = "ALL";
    private String currentFolderPath = null;
    private FileListAdapter adapter;
    private ThumbnailLoader thumbnailLoader;

    private TextView bankBytesText;
    private TextView bankTracksText;
    private ProgressBar bankProgress;
    private TextView selectionText;
    private TextView statusText;
    private ListView fileListView;
    private Button bankButton;
    private Button moveButton;
    private Button pasteButton;
    private Button cancelMoveButton;
    private Button deleteEmptyFolderButton;
    private Button makeTracksButton;
    private Button playButton;
    private Button openFolderButton;
    private Button stopGenerationButton;
    private Button fileAccessButton;
    private ProgressBar busyProgress;
    private Button tabAll, tabPhoto, tabVideo, tabDoc, tabAudio;
    private Button folderUpButton, clearSelectionButton;
    private TextView folderPathText;

    // Android edge-back / Back navigates one folder up; storage root never closes the app.

    private boolean busy = false;
    private boolean selectionMode = false;
    private List<SelectedFile> pendingMoveSelection;
    private Uri lastTrackUri;
    private MediaPlayer mediaPlayer;
    private String selectedOutputFolderPath = "Music/TRASH TO TRACK";
    private Uri lastOutputFolderUri;
    private TextView generateDestinationText;

    private boolean generationReceiverRegistered = false;
    private final BroadcastReceiver generationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (GenerationService.ACTION_STATUS.equals(intent.getAction())) syncGenerationServiceUi();
        }
    };


    private final ActivityResultLauncher<Intent> allFilesAccessLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                reloadFiles();
                updateAccessButton();
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView subtitleText = findViewById(R.id.subtitleText);
        String appVersion = "0.6.0";
        try {
            String installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (installedVersion != null && !installedVersion.isEmpty()) appVersion = installedVersion;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        subtitleText.setText("DELETE DATA / REFINE MUSIC | v" + appVersion);

        // Repair any delete that completed just before a prior process crash.
        // Pending deletes are journaled before touching the source file, so this
        // can safely restore the missing credit exactly once.
        BankStore.recoverPendingDeletes(this);
        if (!isRichEssenceMode()) {
            BankStore.sanitizeForPrivateMode(this);
        }
        BankStore.reconcileLegacyState(this);

        android.view.View rootLayout = findViewById(R.id.rootLayout);
        final int baseTopPadding = rootLayout.getPaddingTop();
        final int baseBottomPadding = rootLayout.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), baseTopPadding + systemBars.top, v.getPaddingRight(), baseBottomPadding + systemBars.bottom);
            return insets;
        });

        bankBytesText = findViewById(R.id.bankBytesText);
        bankTracksText = findViewById(R.id.bankTracksText);
        bankProgress = findViewById(R.id.bankProgress);
        selectionText = findViewById(R.id.selectionText);
        statusText = findViewById(R.id.statusText);
        fileListView = findViewById(R.id.fileListView);
        bankButton = findViewById(R.id.bankButton);
        moveButton = findViewById(R.id.moveButton);
        pasteButton = findViewById(R.id.pasteButton);
        cancelMoveButton = findViewById(R.id.cancelMoveButton);
        deleteEmptyFolderButton = findViewById(R.id.deleteEmptyFolderButton);
        makeTracksButton = findViewById(R.id.makeTracksButton);
        playButton = findViewById(R.id.playButton);
        openFolderButton = findViewById(R.id.openFolderButton);
        stopGenerationButton = findViewById(R.id.stopGenerationButton);
        fileAccessButton = findViewById(R.id.fileAccessButton);
        busyProgress = findViewById(R.id.busyProgress);
        tabAll = findViewById(R.id.tabAll);
        tabPhoto = findViewById(R.id.tabPhoto);
        tabVideo = findViewById(R.id.tabVideo);
        tabDoc = findViewById(R.id.tabDoc);
        tabAudio = findViewById(R.id.tabAudio);
        folderUpButton = findViewById(R.id.folderUpButton);
        clearSelectionButton = findViewById(R.id.clearSelectionButton);
        folderPathText = findViewById(R.id.folderPathText);

        thumbnailLoader = new ThumbnailLoader(this);
        adapter = new FileListAdapter(this, thumbnailLoader, new FileListAdapter.Listener() {
            @Override public void onActivate(SelectedFile file) { handleBrowserEntryTap(file); }
            @Override public void onToggleSelection(SelectedFile file) { toggleSelection(file); }
        });
        fileListView.setAdapter(adapter);
        fileListView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        // Row taps are handled directly by FileListAdapter. This avoids
        // device-specific ListView item-click interception and keeps true
        // multi-selection deterministic.

        findViewById(R.id.apiButton).setOnClickListener(v -> showApiDialog());
        findViewById(R.id.newFolderButton).setOnClickListener(v -> createFolderInCurrent());
        fileAccessButton.setOnClickListener(v -> {
            if (hasAllFilesAccess()) reloadFiles(); else requestAllFilesAccess();
        });
        bankButton.setOnClickListener(v -> confirmBankSelection());
        moveButton.setOnClickListener(v -> startMoveSelection());
        pasteButton.setOnClickListener(v -> pastePendingMoveHere());
        cancelMoveButton.setOnClickListener(v -> cancelPendingMove());
        deleteEmptyFolderButton.setOnClickListener(v -> confirmDeleteEmptyFolders());
        makeTracksButton.setOnClickListener(v -> showGenerateDialog());
        playButton.setOnClickListener(v -> togglePlayback());
        openFolderButton.setOnClickListener(v -> openLastOutputFolder());
        stopGenerationButton.setOnClickListener(v -> stopGeneration());
        folderUpButton.setOnClickListener(v -> navigateFolderUp());
        setupSystemBackNavigation();
        clearSelectionButton.setOnClickListener(v -> clearSelectionFromButton());

        String savedOutputFolder = getSharedPreferences(OUTPUT_PREFS, MODE_PRIVATE)
                .getString(PREF_OUTPUT_FOLDER_PATH, "Music/TRASH TO TRACK");
        selectedOutputFolderPath = SelectedFile.normalizeFolderPath(savedOutputFolder);
        if (selectedOutputFolderPath.isEmpty()) selectedOutputFolderPath = "Music/TRASH TO TRACK";

        syncGenerationServiceUi();

        tabAll.setOnClickListener(v -> setFilter("ALL"));
        tabPhoto.setOnClickListener(v -> setFilter("PHOTO"));
        tabVideo.setOnClickListener(v -> setFilter("VIDEO"));
        tabDoc.setOnClickListener(v -> setFilter("DOC"));
        tabAudio.setOnClickListener(v -> setFilter("AUDIO"));

        refreshBankUi();
        updateSelectionUi();
        updateTabUi();
        ensureFileAccessUi();
        reloadFiles();
    }

    private void ensureFileAccessUi() {
        updateAccessButton();
        if (!hasAllFilesAccess()) {
            new AlertDialog.Builder(this)
                    .setTitle("FULL FILE ACCESS")
                    .setMessage("TRASH TO TRACK内だけでフォルダ閲覧・複数選択・移動・保存を完結させるため、Androidの『すべてのファイルへのアクセス』を一度だけ許可してください。\n\n許可後はフォルダ選択でAndroid標準ピッカーへ飛びません。")
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("GRANT ONCE", (d, w) -> requestAllFilesAccess())
                    .show();
        }
    }

    private boolean hasAllFilesAccess() {
        return Environment.isExternalStorageManager();
    }

    private void requestAllFilesAccess() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            allFilesAccessLauncher.launch(intent);
        } catch (Exception e) {
            try {
                allFilesAccessLauncher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                showError("FULL FILE ACCESSを開けませんでした", e.getMessage());
            }
        }
    }

    private void updateAccessButton() {
        if (fileAccessButton == null) return;
        boolean granted = hasAllFilesAccess();
        fileAccessButton.setText("ALLOW FILE ACCESS");
        fileAccessButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        View addFolder = findViewById(R.id.newFolderButton);
        if (addFolder != null) addFolder.setEnabled(granted && !busy);
    }

    private void setFilter(String filter) {
        if (pendingMoveSelection == null && !selected.isEmpty()) clearBrowseSelection();
        activeFilter = filter;
        if (hasAllFilesAccess()) reloadFiles(); else applyFilter();
        updateTabUi();
    }

    private void updateTabUi() {
        styleTab(tabAll, "ALL".equals(activeFilter));
        styleTab(tabPhoto, "PHOTO".equals(activeFilter));
        styleTab(tabVideo, "VIDEO".equals(activeFilter));
        styleTab(tabDoc, "DOC".equals(activeFilter));
        styleTab(tabAudio, "AUDIO".equals(activeFilter));
    }

    private void styleTab(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.tab_selected : R.drawable.tab_normal);
        button.setTextColor(getColor(active ? R.color.black : R.color.muted));
    }

    private void reloadFiles() {
        if (thumbnailLoader != null) thumbnailLoader.clear();
        if (hasAllFilesAccess()) {
            worker.execute(() -> {
                List<SelectedFile> loaded = FilesystemRepository.listDirectory(currentFolderPath);
                runOnUiThread(() -> {
                    allFiles.clear();
                    allFiles.addAll(loaded);
                    applyFilter();
                    updateSelectionUi();
                    updateAccessButton();
                    if (loaded.isEmpty()) statusText.setText("EMPTY FOLDER");
                });
            });
            return;
        }

        allFiles.clear();
        currentFolderPath = null;
        applyFilter();
        updateSelectionUi();
        updateAccessButton();
        statusText.setText("GRANT FULL FILE ACCESS TO BROWSE AND DELETE");
    }

    private void applyFilter() {
        List<SelectedFile> visible = new ArrayList<>();
        for (SelectedFile f : allFiles) {
            if (f.isFolder() || matchesActiveFilter(f)) visible.add(f);
        }
        visible.sort((a, b) -> {
            if (a.isFolder() != b.isFolder()) return a.isFolder() ? -1 : 1;
            if (a.isFolder()) return a.name.compareToIgnoreCase(b.name);
            return Long.compare(b.dateModifiedSeconds, a.dateModifiedSeconds);
        });
        currentVisibleEntries.clear();
        currentVisibleEntries.addAll(visible);
        adapter.setFiles(visible);
        adapter.setSelectionMode(selectionMode && pendingMoveSelection == null);
        adapter.setSelected(selected.keySet());
        updateFolderUi();
    }

    private boolean matchesActiveFilter(SelectedFile f) {
        switch (activeFilter) {
            case "PHOTO": return f.kind == SelectedFile.Kind.PHOTO;
            case "VIDEO": return f.kind == SelectedFile.Kind.VIDEO;
            case "AUDIO": return f.kind == SelectedFile.Kind.AUDIO;
            case "DOC": return f.kind == SelectedFile.Kind.DOCUMENT || f.kind == SelectedFile.Kind.OTHER;
            default: return true;
        }
    }


    private void handleBrowserEntryTap(SelectedFile entry) {
        if (entry == null) return;
        if (entry.isFolder()) {
            if (pendingMoveSelection == null && !selected.isEmpty()) clearBrowseSelection();
            currentFolderPath = entry.folderPath;
            reloadFiles();
            return;
        }
        if (pendingMoveSelection != null) {
            statusText.setText("MOVE MODE: OPEN DESTINATION, THEN MOVE HERE");
            return;
        }
        showSafePreview(entry);
    }

    private void showSafePreview(SelectedFile file) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.review_bg));
        int pad = dp(18);
        box.setPadding(pad, dp(6), pad, 0);

        TextView safety = new TextView(this);
        safety.setText("SAFE INSPECT  •  Nothing is selected or deleted from this screen.");
        safety.setTextColor(getColor(R.color.dialog_action));
        safety.setTextSize(12);
        box.addView(safety);

        if (file.kind == SelectedFile.Kind.PHOTO || file.kind == SelectedFile.Kind.VIDEO) {
            ImageView preview = new ImageView(this);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
            imageParams.topMargin = dp(12);
            preview.setLayoutParams(imageParams);
            preview.setBackgroundColor(getColor(R.color.panel_2));
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnailLoader.load(file, preview);
            box.addView(preview);
        }

        TextView name = new TextView(this);
        name.setText(file.name);
        name.setTextColor(getColor(R.color.review_text));
        name.setTextSize(17);
        name.setPadding(0, dp(14), 0, dp(6));
        box.addView(name);

        String date = file.dateModifiedSeconds > 0
                ? new SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.getDefault()).format(new Date(file.dateModifiedSeconds * 1000L))
                : "-";
        TextView detail = new TextView(this);
        detail.setText("SIZE  " + LyriaClient.humanBytes(file.sizeBytes)
                + "\nDATE  " + date
                + "\nTYPE  " + file.kind
                + "\nLOCATION  " + file.locationLabel);
        detail.setTextColor(getColor(R.color.review_meta));
        detail.setTextSize(13);
        box.addView(detail);

        new AlertDialog.Builder(this)
                .setTitle("INSPECT FILE")
                .setView(box)
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void setupSystemBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackOrEdgeSwipe();
            }
        });
    }

    /**
     * Android edge-swipe / Back is treated as navigation inside TRASH TO TRACK.
     * It never finishes the Activity from the storage root.
     */
    private void handleBackOrEdgeSwipe() {
        // Folder navigation always wins. Edge-back means exactly one level up.
        if (currentFolderPath != null && !currentFolderPath.trim().isEmpty()) {
            navigateFolderUp();
            return;
        }

        // At STORAGE root, Back never exits the app. It only exits a temporary mode.
        if (pendingMoveSelection != null) {
            cancelPendingMove();
            return;
        }
        if (selectionMode || !selected.isEmpty()) {
            selected.clear();
            selectionMode = false;
            adapter.setSelectionMode(false);
            adapter.setSelected(selected.keySet());
            updateSelectionUi();
            applyFilter();
            statusText.setText("SELECTION CLEARED");
            return;
        }
        statusText.setText("TOP LEVEL");
    }


    private void navigateFolderUp() {
        if (currentFolderPath == null || currentFolderPath.trim().isEmpty()) return;
        if (pendingMoveSelection == null && !selected.isEmpty()) clearBrowseSelection();
        String current = SelectedFile.normalizeFolderPath(currentFolderPath);
        int slash = current.lastIndexOf('/');
        currentFolderPath = slash < 0 ? null : current.substring(0, slash);
        if (hasAllFilesAccess()) reloadFiles(); else applyFilter();
    }

    private void clearBrowseSelection() {
        selected.clear();
        selectionMode = false;
        if (adapter != null) {
            adapter.setSelectionMode(false);
            adapter.setSelected(selected.keySet());
        }
        updateSelectionUi();
    }

    private void updateFolderUi() {
        if (folderPathText == null || folderUpButton == null || clearSelectionButton == null) return;
        boolean atRoot = currentFolderPath == null || currentFolderPath.trim().isEmpty();
        String pathLabel = atRoot ? "STORAGE" : "STORAGE / " + currentFolderPath;
        if (pendingMoveSelection != null) {
            folderPathText.setText("MOVE TO: " + pathLabel);
            folderPathText.setTextColor(getColor(R.color.accent));
        } else {
            folderPathText.setText(pathLabel);
            folderPathText.setTextColor(getColor(R.color.white));
        }
        folderUpButton.setEnabled(!atRoot);

        clearSelectionButton.setVisibility(pendingMoveSelection == null && selectionMode && !selected.isEmpty() ? View.VISIBLE : View.GONE);
        clearSelectionButton.setText("CLEAR");
    }

    private void clearSelectionFromButton() {
        if (pendingMoveSelection != null) return;
        selected.clear();
        selectionMode = false;
        adapter.setSelectionMode(false);
        adapter.setSelected(selected.keySet());
        updateSelectionUi();
        updateFolderUi();
        statusText.setText("READY");
    }

    private void toggleSelection(SelectedFile file) {
        if (file == null) return;
        if (file.isFolder() && FilesystemRepository.isProtectedRootFolder(file.folderPath)) {
            Toast.makeText(this, "Top-level system/media folders are protected. Open the folder and select items inside it instead.", Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingMoveSelection != null) {
            statusText.setText("MOVE MODE: OPEN DESTINATION, THEN MOVE HERE");
            return;
        }
        selectionMode = true;
        if (selected.containsKey(file.key())) selected.remove(file.key());
        else selected.put(file.key(), file);
        if (selected.isEmpty()) selectionMode = false;
        adapter.setSelectionMode(selectionMode);
        adapter.setSelected(selected.keySet());
        updateSelectionUi();
        updateFolderUi();
        if (selectionMode) statusText.setText("SELECTION MODE");
        else statusText.setText("READY");
    }

    private void updateSelectionUi() {
        if (selected.isEmpty() && pendingMoveSelection == null) selectionMode = false;

        long selectedBytes = 0L;
        long creditBytes = 0L;
        int folders = 0;
        int emptyFolders = 0;
        int creditFiles = 0;
        for (SelectedFile f : selected.values()) {
            if (f.isFolder()) {
                folders++;
                if (FilesystemRepository.isEmptyFolder(f.folderPath)) emptyFolders++;
                continue;
            }
            selectedBytes += f.sizeBytes;
            if (f.earnsCredit()) {
                creditBytes += f.sizeBytes;
                creditFiles++;
            }
        }

        boolean moving = pendingMoveSelection != null && !pendingMoveSelection.isEmpty();
        adapter.setSelectionMode(selectionMode && !moving);

        if (moving) {
            selectionText.setText(pendingMoveSelection.size() + " ITEM(S) TO MOVE  •  OPEN DESTINATION FOLDER");
        } else if (selected.isEmpty()) {
            selectionText.setText("TAP: OPEN  ·  HOLD: SELECT");
        } else {
            BankStore.State bankState = BankStore.load(this);
            int currentTracks = bankState.availableTracks();
            int projectedTracks = (int) ((bankState.creditBytes + creditBytes) / BankStore.TRACK_BYTES);
            int unlocks = Math.max(0, projectedTracks - currentTracks);
            String creditLabel = creditFiles > 0
                    ? "  •  +" + LyriaClient.humanBytes(creditBytes) + " CREDIT"
                            + (unlocks > 0 ? "  •  UNLOCKS +" + unlocks + " TRACK" + (unlocks == 1 ? "" : "S") : "")
                    : "";
            String folderLabel = folders > 0 ? "  •  " + folders + " FOLDER" + (folders == 1 ? "" : "S") : "";
            selectionText.setText(selected.size() + " SELECTED" + folderLabel + creditLabel);
        }

        // Selection mode exposes only the two useful actions: MOVE and DELETE -> TRACK CREDIT.
        moveButton.setVisibility(!moving && !selected.isEmpty() ? View.VISIBLE : View.GONE);
        moveButton.setEnabled(!busy && !moving && !selected.isEmpty());
        moveButton.setText("MOVE");

        bankButton.setVisibility(!moving && creditFiles > 0 ? View.VISIBLE : View.GONE);
        bankButton.setEnabled(!busy && !moving && creditFiles > 0);
        bankButton.setText("DELETE → TRACK CREDIT");

        // Empty-folder deletion is separate because folders do not earn music credit.
        boolean showEmptyDelete = !moving && emptyFolders > 0 && creditFiles == 0;
        deleteEmptyFolderButton.setVisibility(showEmptyDelete ? View.VISIBLE : View.GONE);
        deleteEmptyFolderButton.setEnabled(!busy && showEmptyDelete);
        deleteEmptyFolderButton.setText(emptyFolders == 1 ? "DELETE EMPTY FOLDER" : "DELETE " + emptyFolders + " EMPTY FOLDERS");

        pasteButton.setVisibility(moving ? View.VISIBLE : View.GONE);
        pasteButton.setEnabled(!busy && moving);
        pasteButton.setText("MOVE HERE");
        cancelMoveButton.setVisibility(moving ? View.VISIBLE : View.GONE);
        cancelMoveButton.setEnabled(!busy && moving);

        updateFolderUi();
    }

    private void refreshBankUi() {
        BankStore.State state = BankStore.load(this);
        bankBytesText.setText(LyriaClient.humanBytes(state.creditBytes));
        bankTracksText.setText(state.availableTracks() + " TRACK" + (state.availableTracks() == 1 ? "" : "S") + " READY");
        long remainder = state.creditBytes % BankStore.TRACK_BYTES;
        int progress = (int) Math.round(remainder * 100.0 / BankStore.TRACK_BYTES);
        bankProgress.setProgress(progress);
        makeTracksButton.setEnabled(!busy && pendingMoveSelection == null && state.availableTracks() > 0);
        makeTracksButton.setText(state.availableTracks() > 0 ? "MAKE TRACKS" : "25 MB TO NEXT");
    }

    private void startMoveSelection() {
        if (selected.isEmpty() || busy) return;
        if (!hasAllFilesAccess()) { requestAllFilesAccess(); return; }
        pendingMoveSelection = new ArrayList<>(selected.values());
        selectionMode = false;
        adapter.setSelectionMode(false);
        setBusy(false, "MOVE MODE: OPEN DESTINATION, THEN MOVE HERE");
        updateSelectionUi();
    }

    private void pastePendingMoveHere() {
        if (busy || pendingMoveSelection == null || pendingMoveSelection.isEmpty()) return;
        String destination = currentFolderPath == null ? "" : currentFolderPath;
        confirmMoveSelection(new ArrayList<>(pendingMoveSelection), destination);
    }

    private void cancelPendingMove() {
        if (busy) return;
        pendingMoveSelection = null;
        selected.clear();
        selectionMode = false;
        adapter.setSelectionMode(false);
        adapter.setSelected(selected.keySet());
        statusText.setText("MOVE CANCELED");
        updateSelectionUi();
        updateFolderUi();
    }

    private void confirmDeleteEmptyFolders() {
        if (busy || pendingMoveSelection != null) return;
        List<SelectedFile> emptyFolders = new ArrayList<>();
        for (SelectedFile item : selected.values()) {
            if (item.isFolder() && FilesystemRepository.isEmptyFolder(item.folderPath)) emptyFolders.add(item);
        }
        if (emptyFolders.isEmpty()) {
            Toast.makeText(this, "空のフォルダを選択してください。", Toast.LENGTH_LONG).show();
            return;
        }
        AlertDialog emptyFolderDialog = new AlertDialog.Builder(this)
                .setTitle(emptyFolders.size() == 1 ? "DELETE EMPTY FOLDER" : "DELETE EMPTY FOLDERS")
                .setMessage(emptyFolders.size() + " empty folder(s) will be permanently deleted. Non-empty folders are never deleted by this action.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (d, w) -> deleteEmptyFolders(emptyFolders))
                .create();
        emptyFolderDialog.setOnShowListener(ignored -> {
            emptyFolderDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.black));
            emptyFolderDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
        });
        emptyFolderDialog.show();
    }

    private void deleteEmptyFolders(List<SelectedFile> folders) {
        if (folders == null || folders.isEmpty()) return;
        setBusy(true, "DELETING EMPTY FOLDERS...");
        worker.execute(() -> {
            int deleted = 0;
            int failed = 0;
            for (SelectedFile folder : folders) {
                if (FilesystemRepository.deleteEmptyFolder(folder.folderPath)) deleted++; else failed++;
            }
            int deletedFinal = deleted;
            int failedFinal = failed;
            runOnUiThread(() -> {
                for (SelectedFile folder : folders) selected.remove(folder.key());
                setBusy(false, failedFinal == 0
                        ? "DELETED " + deletedFinal + " EMPTY FOLDER" + (deletedFinal == 1 ? "" : "S")
                        : "DELETED " + deletedFinal + " | " + failedFinal + " NOT EMPTY / FAILED");
                reloadFiles();
            });
        });
    }

    private void confirmMoveSelection(List<SelectedFile> snapshot, String destinationRelativePath) {
        if (snapshot == null || snapshot.isEmpty()) return;
        String destination = destinationRelativePath == null || destinationRelativePath.trim().isEmpty()
                ? "STORAGE" : "STORAGE / " + destinationRelativePath;
        long total = 0L;
        for (SelectedFile file : snapshot) total += file.sizeBytes;
        new AlertDialog.Builder(this)
                .setTitle("MOVE SELECTED FILES")
                .setMessage(snapshot.size() + " item(s) / " + LyriaClient.humanBytes(total)
                        + " will be moved to:\n\n" + destination
                        + "\n\nEverything stays inside TRASH TO TRACK. MOVE does not add TRACK CREDIT.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("MOVE", (d, w) -> prepareDirectMove(snapshot, destinationRelativePath))
                .show();
    }

    private void prepareDirectMove(List<SelectedFile> snapshot, String destinationRelativePath) {
        setBusy(true, "MOVING FILES…");
        worker.execute(() -> {
            int moved = 0;
            int failed = 0;
            String firstError = null;
            for (SelectedFile source : snapshot) {
                try {
                    FileMover.moveDirect(this, source, destinationRelativePath);
                    moved++;
                } catch (Exception e) {
                    failed++;
                    if (firstError == null) firstError = e.getMessage();
                }
            }
            int movedFinal = moved;
            int failedFinal = failed;
            String errorFinal = firstError;
            runOnUiThread(() -> {
                pendingMoveSelection = null;
                selected.clear();
                selectionMode = false;
                adapter.setSelectionMode(false);
                currentFolderPath = SelectedFile.normalizeFolderPath(destinationRelativePath);
                setBusy(false, failedFinal == 0
                        ? "MOVED " + movedFinal + " ITEM" + (movedFinal == 1 ? "" : "S")
                        : "MOVED " + movedFinal + " | " + failedFinal + " FAILED");
                if (failedFinal > 0) {
                    Toast.makeText(this, failedFinal + " item(s) failed"
                            + (errorFinal == null ? "" : ": " + errorFinal), Toast.LENGTH_LONG).show();
                }
                reloadFiles();
            });
        });
    }

    private void createFolderInCurrent() {
        if (!hasAllFilesAccess()) { requestAllFilesAccess(); return; }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Folder name");
        input.setTextColor(getColor(R.color.review_text));
        input.setHintTextColor(getColor(R.color.review_meta));
        new AlertDialog.Builder(this)
                .setTitle("NEW FOLDER")
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CREATE", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!FilesystemRepository.createFolder(currentFolderPath, name)) {
                        Toast.makeText(this, "フォルダを作成できませんでした。", Toast.LENGTH_LONG).show();
                    }
                    reloadFiles();
                }).show();
    }






    private void confirmBankSelection() {
        if (selected.isEmpty() || busy) return;
        List<SelectedFile> candidates = new ArrayList<>();
        for (SelectedFile f : selected.values()) if (f.earnsCredit()) candidates.add(f);
        if (candidates.isEmpty()) {
            Toast.makeText(this, "削除できる端末内ファイルを選択してください。", Toast.LENGTH_LONG).show();
            return;
        }
        showDeleteReview(candidates);
    }

    private void showDeleteReview(List<SelectedFile> candidates) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(getColor(R.color.review_bg));
        int pad = dp(14);
        list.setPadding(pad, dp(4), pad, dp(8));

        TextView warning = new TextView(this);
        warning.setText("DELETE REVIEW\nOnly checked files will be deleted. Uncheck anything you want to keep.");
        warning.setTextColor(getColor(R.color.danger));
        warning.setTextSize(13);
        warning.setPadding(0, 0, 0, dp(8));
        list.addView(warning);

        List<CheckBox> checks = new ArrayList<>();
        long nowSeconds = System.currentTimeMillis() / 1000L;
        for (SelectedFile f : candidates) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(true);
            boolean recent = f.dateModifiedSeconds > 0 && nowSeconds - f.dateModifiedSeconds < 7L * 24L * 60L * 60L;
            String recentLabel = recent ? "  [RECENT]" : "";
            cb.setText(f.name + recentLabel + "\n" + LyriaClient.humanBytes(f.sizeBytes) + "  •  " + f.locationLabel);
            cb.setTextColor(getColor(recent ? R.color.danger : R.color.review_text));
            cb.setTextSize(12);
            cb.setPadding(0, dp(5), 0, dp(5));
            cb.setTag(f);
            checks.add(cb);
            list.addView(cb);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("REVIEW BEFORE DELETE")
                .setView(scroll)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONTINUE", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.black));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<SelectedFile> approved = new ArrayList<>();
                for (CheckBox cb : checks) if (cb.isChecked()) approved.add((SelectedFile) cb.getTag());
                if (approved.isEmpty()) {
                    Toast.makeText(this, "削除対象がありません。", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                showFinalDeleteConfirmation(approved);
            });
        });
        dialog.show();
    }

    private void showFinalDeleteConfirmation(List<SelectedFile> approved) {
        long credit = 0L;
        int recentCount = 0;
        long nowSeconds = System.currentTimeMillis() / 1000L;
        for (SelectedFile f : approved) {
            credit += f.sizeBytes;
            if (f.dateModifiedSeconds > 0 && nowSeconds - f.dateModifiedSeconds < 7L * 24L * 60L * 60L) recentCount++;
        }
        final boolean richEssence = isRichEssenceMode();
        String recentWarning = recentCount > 0
                ? "\n\nWARNING: " + recentCount + " file(s) were modified within the last 7 days."
                : "";
        String privacyLine = richEssence
                ? "RICH ESSENCE MODE is ON."
                : "PRIVATE MODE is ON: only abstract metadata is banked.";
        String message = approved.size() + " file(s) / " + LyriaClient.humanBytes(credit)
                + " will be PERMANENTLY deleted." + recentWarning
                + "\n\n" + privacyLine
                + "\n\nThis is the final confirmation. Deleted originals cannot be restored by TRASH TO TRACK.";

        AlertDialog finalDialog = new AlertDialog.Builder(this)
                .setTitle("PERMANENT DELETE")
                .setMessage(message)
                .setNegativeButton("KEEP FILES", null)
                .setPositiveButton("DELETE " + approved.size() + " FILE(S)", (d, w) -> prepareBank(approved, richEssence))
                .create();
        finalDialog.setOnShowListener(ignored -> {
            finalDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.black));
            finalDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
        });
        finalDialog.show();
    }

    private void prepareBank(List<SelectedFile> snapshot, boolean richEssenceMode) {
        if (!hasAllFilesAccess()) { requestAllFilesAccess(); return; }
        setBusy(true, "EXTRACTING ESSENCE…");
        worker.execute(() -> {
            long bankedBytes = 0L;
            List<String> failures = new ArrayList<>();
            boolean pendingRecovery = false;

            // Resolve any interrupted transaction before starting a new batch.
            BankStore.recoverPendingDeletes(this);

            for (SelectedFile file : snapshot) {
                if (file == null || file.isFolder() || !file.earnsCredit()
                        || file.source != SelectedFile.Source.DEVICE_FS
                        || !"file".equalsIgnoreCase(file.uri.getScheme())) {
                    if (file != null) failures.add(file.name);
                    continue;
                }

                EssenceRecord essence = FileAnalyzer.createEssence(this, file, true, richEssenceMode);
                File raw = new File(file.uri.getPath());
                String rawPath = raw.getAbsolutePath();

                // Journal first. If internal BANK persistence is unavailable,
                // abort BEFORE deleting the source file.
                String txId = BankStore.preparePendingDelete(this, rawPath, essence);
                if (txId == null) {
                    FileAnalyzer.discardEssence(essence);
                    failures.add(file.name);
                    break;
                }

                try {
                    if (!raw.isFile() || !raw.delete()) {
                        failures.add(file.name);
                        BankStore.cancelPendingDelete(this, txId);
                        continue;
                    }

                    boolean committed = BankStore.commitPendingDelete(this, txId);
                    if (!committed) {
                        // The source is already gone, but the journal is durable.
                        // Retry immediately; if storage is still unavailable,
                        // leave the journal for automatic next-launch recovery.
                        committed = BankStore.recoverPendingDeletes(this) > 0;
                    }

                    if (committed) {
                        bankedBytes += essence.sourceBytes;
                        android.media.MediaScannerConnection.scanFile(this,
                                new String[]{rawPath}, null, null);
                    } else {
                        pendingRecovery = true;
                        break; // Do not delete any more files until BANK is healthy.
                    }
                } catch (Exception e) {
                    failures.add(file.name);
                    if (raw.exists()) BankStore.cancelPendingDelete(this, txId);
                    else pendingRecovery = true;
                    if (pendingRecovery) break;
                }
            }

            final long credited = bankedBytes;
            final int failedCount = failures.size();
            final boolean hasPendingRecovery = pendingRecovery;
            runOnUiThread(() -> {
                selected.clear();
                selectionMode = false;
                adapter.setSelectionMode(false);
                String finalStatus;
                if (hasPendingRecovery) {
                    finalStatus = "DELETE RECORDED • CREDIT RECOVERS AUTOMATICALLY";
                } else if (credited > 0L) {
                    finalStatus = "BANKED " + LyriaClient.humanBytes(credited);
                } else {
                    finalStatus = "NOTHING DELETED";
                }
                setBusy(false, finalStatus);
                refreshBankUi();
                if (hasPendingRecovery) {
                    Toast.makeText(this,
                            "A deleted item is safely journaled. Its TRACK credit will be recovered automatically when BANK storage is available.",
                            Toast.LENGTH_LONG).show();
                } else if (failedCount > 0) {
                    Toast.makeText(this,
                            failedCount + " item(s) were kept because deletion or BANK recording did not complete.",
                            Toast.LENGTH_LONG).show();
                }
                reloadFiles();
            });
        });
    }



    private boolean isRichEssenceMode() {
        return getSharedPreferences(PRIVACY_PREFS, MODE_PRIVATE).getBoolean(PREF_RICH_ESSENCE, false);
    }

    private void setRichEssenceMode(boolean enabled) {
        getSharedPreferences(PRIVACY_PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RICH_ESSENCE, enabled).apply();
        if (!enabled) BankStore.sanitizeForPrivateMode(this);
    }

    private void showApiDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.review_bg));
        int p = dp(20);
        box.setPadding(p, dp(4), p, 0);

        TextView info = new TextView(this);
        info.setText(SecureKeyStore.hasKey(this)
                ? "Gemini API Key is saved encrypted with Android Keystore. Enter a new key only to replace it."
                : "Enter your Gemini API Key. It is stored encrypted with Android Keystore.");
        info.setTextColor(getColor(R.color.review_meta));

        EditText input = new EditText(this);
        input.setHint("Gemini API Key");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(getColor(R.color.review_text));
        input.setHintTextColor(getColor(R.color.review_meta));

        CheckBox richCheck = new CheckBox(this);
        richCheck.setText("RICH ESSENCE MODE (optional)");
        richCheck.setTextColor(getColor(R.color.review_text));
        richCheck.setChecked(isRichEssenceMode());
        richCheck.setPadding(0, dp(12), 0, 0);

        TextView privacy = new TextView(this);
        privacy.setText("OFF is recommended and is the default. PRIVATE MODE keeps only abstract local measurements and sends no source mini-images or document wording. All Gemini/Lyria requests use store:false. Turn RICH ESSENCE ON only if you explicitly want tiny visuals/text traces retained and eligible to be sent for richer musical cues.");
        privacy.setTextColor(getColor(R.color.review_meta));
        privacy.setTextSize(11f);

        box.addView(info);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        box.addView(richCheck);
        box.addView(privacy);

        new AlertDialog.Builder(this)
                .setTitle("GEMINI BYOK / PRIVACY")
                .setView(box)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (d, w) -> {
                    boolean rich = richCheck.isChecked();
                    setRichEssenceMode(rich);
                    String key = input.getText().toString().trim();
                    if (!key.isEmpty()) {
                        try {
                            SecureKeyStore.save(this, key);
                        } catch (Exception e) {
                            showError("API Keyを保存できませんでした", e.getMessage());
                            return;
                        }
                    }
                    Toast.makeText(this,
                            (rich ? "RICH ESSENCE MODE ON" : "PRIVATE MODE ON")
                                    + (key.isEmpty() ? " · API key unchanged" : " · API key saved"),
                            Toast.LENGTH_LONG).show();
                }).show();
    }

    private void showGenerateDialog() {
        BankStore.State state = BankStore.load(this);
        int available = state.availableTracks();
        if (available < 1) return;
        if (!SecureKeyStore.hasKey(this)) {
            Toast.makeText(this, "先にGemini API Keyを保存してください。", Toast.LENGTH_LONG).show();
            showApiDialog();
            return;
        }
        if (!hasAllFilesAccess()) { requestAllFilesAccess(); return; }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.review_bg));
        int p = dp(20); box.setPadding(p, dp(8), p, 0);

        TextView destinationLabel = makeDialogLabel("SAVE FOLDER");
        generateDestinationText = new TextView(this);
        generateDestinationText.setText("SAVE FOLDER: STORAGE / " + selectedOutputFolderPath);
        generateDestinationText.setTextColor(getColor(R.color.review_text));
        generateDestinationText.setTextSize(12f);
        generateDestinationText.setPadding(0, dp(4), 0, dp(6));
        Button chooseFolderButton = new Button(this);
        chooseFolderButton.setText("CHOOSE / CREATE SAVE FOLDER");
        chooseFolderButton.setTextSize(11f);
        chooseFolderButton.setTextColor(getColor(R.color.white));
        chooseFolderButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.line)));
        chooseFolderButton.setOnClickListener(v -> InAppFolderPicker.show(this, "SAVE TRACKS TO",
                selectedOutputFolderPath, chosen -> {
                    selectedOutputFolderPath = SelectedFile.normalizeFolderPath(chosen);
                    getSharedPreferences(OUTPUT_PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_OUTPUT_FOLDER_PATH, selectedOutputFolderPath).apply();
                    if (generateDestinationText != null) {
                        generateDestinationText.setText("SAVE FOLDER: "
                                + (selectedOutputFolderPath.isEmpty() ? "STORAGE" : "STORAGE / " + selectedOutputFolderPath));
                    }
                }));

        TextView folderNote = new TextView(this);
        folderNote.setText("Folder browsing and new-folder creation stay inside TRASH TO TRACK. Tracks are saved directly in the selected folder.");
        folderNote.setTextColor(getColor(R.color.review_meta));
        folderNote.setTextSize(11f);
        folderNote.setPadding(0, dp(4), 0, dp(4));

        TextView genreLabel = makeDialogLabel("GENRE");
        Spinner genreSpinner = new Spinner(this);
        ArrayAdapter<String> genreAdapter = makeDialogSpinnerAdapter(java.util.Arrays.asList(GENRES));
        genreSpinner.setAdapter(genreAdapter);

        TextView countLabel = makeDialogLabel("QUANTITY");
        Spinner countSpinner = new Spinner(this);
        List<Integer> counts = new ArrayList<>();
        List<String> countLabels = new ArrayList<>();
        counts.add(1); countLabels.add("1 TRACK");
        if (available >= 3) { counts.add(3); countLabels.add("3 TRACKS"); }
        if (available > 1 && available != 3) { counts.add(available); countLabels.add("ALL - " + available + " TRACKS"); }
        ArrayAdapter<String> countAdapter = makeDialogSpinnerAdapter(countLabels);
        countSpinner.setAdapter(countAdapter);

        final boolean richEssence = isRichEssenceMode();
        TextView note = new TextView(this);
        note.setText(richEssence
                ? "RICH ESSENCE MODE ON: tiny retained visuals/text traces may be sent. store:false. 1 TRACK = 25 MB."
                : "PRIVATE MODE ON: no source image/document wording is sent. store:false. 1 TRACK = 25 MB.");
        note.setTextColor(getColor(R.color.review_meta));
        note.setTextSize(12f);
        note.setPadding(0, dp(14), 0, 0);

        box.addView(destinationLabel);
        box.addView(generateDestinationText);
        box.addView(chooseFolderButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        box.addView(folderNote);
        box.addView(genreLabel); box.addView(genreSpinner);
        box.addView(countLabel); box.addView(countSpinner);
        box.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("REFINE TRACKS")
                .setView(box)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("GENERATE", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            File output = FilesystemRepository.resolve(selectedOutputFolderPath);
            if (!output.isDirectory() && !output.mkdirs()) {
                Toast.makeText(this, "保存先フォルダを作成できません。", Toast.LENGTH_LONG).show();
                return;
            }
            int index = countSpinner.getSelectedItemPosition();
            int count = counts.get(Math.max(0, Math.min(index, counts.size() - 1)));
            String genre = String.valueOf(genreSpinner.getSelectedItem());
            String outputPath = selectedOutputFolderPath;
            dialog.dismiss();
            confirmGenerationCalls(count, genre, outputPath, richEssence);
        }));
        dialog.setOnDismissListener(ignored -> generateDestinationText = null);
        dialog.show();
    }

    private ArrayAdapter<String> makeDialogSpinnerAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = view.findViewById(android.R.id.text1);
                if (text != null) text.setTextColor(getColor(R.color.review_text));
                view.setBackgroundColor(getColor(R.color.review_bg));
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView text = view.findViewById(android.R.id.text1);
                if (text != null) text.setTextColor(getColor(R.color.review_text));
                view.setBackgroundColor(getColor(R.color.review_bg));
                return view;
            }
        };
    }

    private TextView makeDialogLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(getColor(R.color.dialog_action));
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, dp(12), 0, dp(4));
        return v;
    }

    private void confirmGenerationCalls(int count, String genre, String outputRelativePath, boolean richEssenceMode) {
        String displayPath = outputRelativePath == null || outputRelativePath.trim().isEmpty()
                ? "STORAGE" : "STORAGE / " + outputRelativePath;
        new AlertDialog.Builder(this)
                .setTitle("GENERATE " + count + " TRACK" + (count == 1 ? "" : "S"))
                .setMessage(count + " Lyria API call" + (count == 1 ? "" : "s") + " will use your Gemini API key.\n\n"
                        + (richEssenceMode
                        ? "RICH ESSENCE MODE: tiny retained visuals/text traces may be sent."
                        : "PRIVATE MODE: no source images or document wording will be sent; only abstract measurements/metadata.")
                        + "\nAll requests explicitly set store:false.\n\n"
                        + "Genre: " + genre
                        + "\nSave directly to: " + displayPath
                        + "\nCredit used: " + LyriaClient.humanBytes(BankStore.TRACK_BYTES * count))
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("GO", (d, w) -> generateTracks(count, genre, outputRelativePath, richEssenceMode))
                .show();
    }

    private void stopGeneration() {
        GenerationService.State state = GenerationService.readState(this);
        if (!state.running) {
            syncGenerationServiceUi();
            return;
        }
        Intent stopIntent = new Intent(this, GenerationService.class);
        stopIntent.setAction(GenerationService.ACTION_CANCEL);
        startService(stopIntent);
        statusText.setText("STOPPING…");
        stopGenerationButton.setEnabled(false);
    }

    private void generateTracks(int count, String genre, String outputRelativePath, boolean richEssenceMode) {
        String apiKey = SecureKeyStore.load(this);
        if (apiKey == null || apiKey.trim().isEmpty()) return;
        if (GenerationService.readState(this).running) {
            Toast.makeText(this, "TRACK generation is already running.", Toast.LENGTH_SHORT).show();
            syncGenerationServiceUi();
            return;
        }
        if (!hasAllFilesAccess()) { requestAllFilesAccess(); return; }

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 901);
        }

        Intent serviceIntent = new Intent(this, GenerationService.class);
        serviceIntent.setAction(GenerationService.ACTION_START);
        serviceIntent.putExtra(GenerationService.EXTRA_COUNT, count);
        serviceIntent.putExtra(GenerationService.EXTRA_GENRE, genre);
        serviceIntent.putExtra(GenerationService.EXTRA_OUTPUT_PATH, SelectedFile.normalizeFolderPath(outputRelativePath));
        serviceIntent.putExtra(GenerationService.EXTRA_FOLDER_NAME, outputRelativePath);
        serviceIntent.putExtra(GenerationService.EXTRA_RICH_ESSENCE, richEssenceMode);
        ContextCompat.startForegroundService(this, serviceIntent);
        setBusy(true, (richEssenceMode ? "RICH" : "PRIVATE") + " | STARTING BACKGROUND GENERATION…");
    }








    private void openLastOutputFolder() {
        if (lastOutputFolderUri == null) return;
        if ("file".equalsIgnoreCase(lastOutputFolderUri.getScheme())) {
            File dir = new File(lastOutputFolderUri.getPath());
            currentFolderPath = FilesystemRepository.toRelative(dir);
            activeFilter = "AUDIO";
            updateTabUi();
            reloadFiles();
            return;
        }
        Toast.makeText(this, "この保存先は旧バージョン形式です。新しい生成先はアプリ内で開けます。", Toast.LENGTH_LONG).show();
    }

    private void togglePlayback() {
        if (lastTrackUri == null) return;
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause(); playButton.setText("PLAY LAST TRACK"); return;
            }
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
                if ("file".equalsIgnoreCase(lastTrackUri.getScheme())) mediaPlayer.setDataSource(lastTrackUri.getPath());
                else mediaPlayer.setDataSource(this, lastTrackUri);
                mediaPlayer.setOnPreparedListener(mp -> { mp.start(); playButton.setText("PAUSE"); });
                mediaPlayer.setOnCompletionListener(mp -> playButton.setText("PLAY LAST TRACK"));
                mediaPlayer.prepareAsync();
            } else {
                mediaPlayer.start(); playButton.setText("PAUSE");
            }
        } catch (Exception e) {
            releasePlayer(); showError("再生できませんでした", e.getMessage());
        }
    }

    private void syncGenerationServiceUi() {
        GenerationService.State state = GenerationService.readState(this);
        if (state.lastTrackUri != null) {
            lastTrackUri = state.lastTrackUri;
            playButton.setVisibility(View.VISIBLE);
        }
        if (state.lastFolderUri != null) {
            lastOutputFolderUri = state.lastFolderUri;
            openFolderButton.setVisibility(View.VISIBLE);
        }
        refreshBankUi();
        setBusy(state.running, state.status);
        stopGenerationButton.setVisibility(state.running ? View.VISIBLE : View.GONE);
        stopGenerationButton.setEnabled(state.running);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!generationReceiverRegistered) {
            ContextCompat.registerReceiver(this, generationReceiver,
                    new IntentFilter(GenerationService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
            generationReceiverRegistered = true;
        }
        syncGenerationServiceUi();
    }

    @Override
    protected void onStop() {
        if (generationReceiverRegistered) {
            try { unregisterReceiver(generationReceiver); } catch (Exception ignored) {}
            generationReceiverRegistered = false;
        }
        super.onStop();
    }

    private void setBusy(boolean value, String status) {
        busy = value;
        busyProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        statusText.setText(status);
        bankButton.setEnabled(!value && !selected.isEmpty());
        makeTracksButton.setEnabled(!value && BankStore.load(this).availableTracks() > 0);
        findViewById(R.id.newFolderButton).setEnabled(!value);
        findViewById(R.id.apiButton).setEnabled(!value);
        fileAccessButton.setEnabled(!value);
        // Keep browsing and multi-selection active even while GenerationService is busy.
        // Only actions that mutate files/bank are disabled during generation.
        fileListView.setEnabled(true);
        updateSelectionUi();
        refreshBankUi();
    }


    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(message == null || message.trim().isEmpty() ? "Unknown error" : message)
                .setPositiveButton("OK", null).show();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        if (thumbnailLoader != null) thumbnailLoader.shutdown();
        worker.shutdownNow();
        super.onDestroy();
    }



}
