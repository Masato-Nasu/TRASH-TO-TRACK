package dev.masatolab.trashtotrack;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileListAdapter extends BaseAdapter {
    public interface Listener {
        void onActivate(SelectedFile file);
        void onToggleSelection(SelectedFile file);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final ThumbnailLoader thumbnails;
    private final Listener listener;
    private final List<SelectedFile> files = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.getDefault());
    private boolean selectionMode = false;

    public FileListAdapter(Context context, ThumbnailLoader thumbnails, Listener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.thumbnails = thumbnails;
        this.listener = listener;
    }

    public void setFiles(List<SelectedFile> newFiles) {
        files.clear();
        if (newFiles != null) files.addAll(newFiles);
        notifyDataSetChanged();
    }

    public void setSelected(Set<String> keys) {
        selected.clear();
        if (keys != null) selected.addAll(keys);
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return files.size(); }
    @Override public SelectedFile getItem(int position) { return files.get(position); }
    @Override public long getItemId(int position) { return files.get(position).key().hashCode(); }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_file, parent, false);
            h = new Holder();
            h.icon = convertView.findViewById(R.id.fileIcon);
            h.check = convertView.findViewById(R.id.fileCheck);
            h.name = convertView.findViewById(R.id.fileName);
            h.meta = convertView.findViewById(R.id.fileMeta);
            h.source = convertView.findViewById(R.id.fileSource);
            convertView.setTag(h);
        } else h = (Holder) convertView.getTag();

        SelectedFile file = getItem(position);
        final SelectedFile boundFile = file;
        boolean isSelected = selected.contains(file.key());

        convertView.setOnClickListener(null);
        convertView.setOnLongClickListener(null);
        h.check.setOnClickListener(null);
        convertView.setClickable(true);
        convertView.setFocusable(false);
        h.check.setChecked(isSelected);
        h.check.setClickable(selectionMode);
        h.check.setFocusable(false);
        h.check.setFocusableInTouchMode(false);
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        convertView.setBackgroundResource(isSelected ? R.drawable.row_selected : R.drawable.row_normal);

        if (file.isFolder()) {
            h.name.setText(file.name);
            h.meta.setText("FOLDER");
            h.meta.setTextColor(context.getColor(R.color.muted));
            h.source.setText(selectionMode ? (isSelected ? "SELECTED" : "TAP TO SELECT") : "OPEN FOLDER");
            h.source.setTextColor(context.getColor(selectionMode ? R.color.accent : R.color.muted));
            h.icon.setTag(file.key());
            h.icon.setImageResource(R.drawable.ic_folder);
            h.icon.setPadding(8, 8, 8, 8);
        } else {
            h.name.setText(file.name);
            String date = file.dateModifiedSeconds > 0 ? dateFormat.format(new Date(file.dateModifiedSeconds * 1000L)) : "-";
            long nowSeconds = System.currentTimeMillis() / 1000L;
            boolean recent = file.dateModifiedSeconds > 0
                    && nowSeconds - file.dateModifiedSeconds < 7L * 24L * 60L * 60L;
            h.meta.setText(LyriaClient.humanBytes(file.sizeBytes) + "  |  " + date + (recent ? "  |  RECENT" : ""));
            h.meta.setTextColor(context.getColor(recent ? R.color.danger : R.color.muted));
            h.source.setText(selectionMode ? (isSelected ? "SELECTED" : "TAP TO SELECT") : file.locationLabel);
            h.source.setTextColor(context.getColor(selectionMode ? R.color.accent : R.color.muted));
            h.icon.setPadding(10, 10, 10, 10);
            thumbnails.load(file, h.icon);
        }

        convertView.setOnClickListener(v -> {
            if (selectionMode) listener.onToggleSelection(boundFile);
            else listener.onActivate(boundFile);
        });
        convertView.setOnLongClickListener(v -> {
            listener.onToggleSelection(boundFile);
            return true;
        });
        h.check.setOnClickListener(v -> listener.onToggleSelection(boundFile));
        return convertView;
    }

    private static final class Holder {
        ImageView icon;
        CheckBox check;
        TextView name;
        TextView meta;
        TextView source;
    }
}
