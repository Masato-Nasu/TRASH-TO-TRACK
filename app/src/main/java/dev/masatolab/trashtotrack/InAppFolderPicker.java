package dev.masatolab.trashtotrack;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class InAppFolderPicker {
    public interface Listener { void onFolderChosen(String relativePath); }
    private InAppFolderPicker() {}

    public static void show(Context context, String title, String initialRelativePath, Listener listener) {
        final String[] current = { SelectedFile.normalizeFolderPath(initialRelativePath) };

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(context.getColor(R.color.review_bg));
        int pad = dp(context, 16);
        root.setPadding(pad, dp(context, 8), pad, 0);

        TextView path = new TextView(context);
        path.setTextSize(13f);
        path.setTypeface(null, Typeface.BOLD);
        path.setTextColor(context.getColor(R.color.review_text));
        path.setPadding(0, dp(context, 4), 0, dp(context, 8));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button up = new Button(context); up.setText("UP");
        up.setTextColor(context.getColor(R.color.white));
        up.setBackgroundTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.line)));
        Button newFolder = new Button(context); newFolder.setText("+ FOLDER");
        newFolder.setTextColor(context.getColor(R.color.white));
        newFolder.setBackgroundTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.line)));
        actions.addView(up, new LinearLayout.LayoutParams(0, dp(context, 44), 1f));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, dp(context, 44), 1f); np.setMarginStart(dp(context, 8));
        actions.addView(newFolder, np);

        ListView list = new ListView(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = view.findViewById(android.R.id.text1);
                if (text != null) {
                    text.setTextColor(context.getColor(R.color.review_text));
                    text.setTextSize(15f);
                }
                return view;
            }
        };
        list.setAdapter(adapter);

        root.addView(path);
        root.addView(actions);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 360)));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("USE THIS FOLDER", null)
                .create();

        final List<File> shown = new ArrayList<>();
        Runnable refresh = () -> {
            File dir = FilesystemRepository.resolve(current[0]);
            current[0] = FilesystemRepository.toRelative(dir);
            path.setText(current[0].isEmpty() ? "STORAGE" : "STORAGE / " + current[0]);
            up.setEnabled(!current[0].isEmpty());
            shown.clear();
            File[] dirs = dir.listFiles(File::isDirectory);
            if (dirs != null) {
                Arrays.sort(dirs, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
                for (File f : dirs) if (f.canRead()) shown.add(f);
            }
            adapter.clear();
            for (File f : shown) adapter.add("[DIR]  " + f.getName());
            adapter.notifyDataSetChanged();
        };

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shown.size()) return;
            current[0] = FilesystemRepository.toRelative(shown.get(position));
            refresh.run();
        });
        up.setOnClickListener(v -> {
            File dir = FilesystemRepository.resolve(current[0]);
            File parent = dir.getParentFile();
            if (parent != null) current[0] = FilesystemRepository.toRelative(parent);
            refresh.run();
        });
        newFolder.setOnClickListener(v -> {
            EditText input = new EditText(context);
            input.setSingleLine(true);
            input.setHint("Folder name");
            input.setTextColor(context.getColor(R.color.review_text));
            input.setHintTextColor(context.getColor(R.color.review_meta));
            new AlertDialog.Builder(context)
                    .setTitle("NEW FOLDER")
                    .setView(input)
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CREATE", (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (!FilesystemRepository.createFolder(current[0], name)) {
                            Toast.makeText(context, "フォルダを作成できませんでした。", Toast.LENGTH_LONG).show();
                            return;
                        }
                        current[0] = SelectedFile.normalizeFolderPath(current[0].isEmpty() ? name : current[0] + "/" + name);
                        refresh.run();
                    }).show();
        });

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (listener != null) listener.onFolderChosen(current[0]);
            dialog.dismiss();
        }));
        dialog.show();
        refresh.run();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
