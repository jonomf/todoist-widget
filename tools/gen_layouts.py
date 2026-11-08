#!/usr/bin/env python3
"""
Generates the widget layouts (app/src/main/res/layout/*.xml) from shared pieces so every size
stays consistent. Edit here, then run:  python3 tools/gen_layouts.py

View ids the renderer relies on:
  widget_tile:            root, count_big, title, update
  widget_list/_grid:      root, header, app_icon, title_area, title, overdue, count, status,
                          update, refresh, add, list, empty
  row_compact:            row, check, content, due
  row_detail:             row, check, content, due, meta
  row_more:               more
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'res', 'layout')
H = '<?xml version="1.0" encoding="utf-8"?>\n'
NS = ' xmlns:android="http://schemas.android.com/apk/res/android"'

HEADER = '''
    <LinearLayout
        android:id="@+id/header"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingBottom="2dp">

        <ImageView
            android:id="@+id/app_icon"
            android:layout_width="18dp"
            android:layout_height="18dp"
            android:layout_marginEnd="5dp"
            android:contentDescription="Open in Todoist"
            android:visibility="gone" />

        <LinearLayout
            android:id="@+id/title_area"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/title"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:ellipsize="end"
                android:fontFamily="sans-serif-medium"
                android:singleLine="true"
                android:text="Todoist"
                android:textColor="@color/widget_text"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/overdue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="4dp"
                android:fontFamily="sans-serif-medium"
                android:singleLine="true"
                android:textColor="@color/p1"
                android:textSize="12sp"
                android:visibility="gone" />

            <TextView
                android:id="@+id/count"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="4dp"
                android:singleLine="true"
                android:textColor="@color/widget_subtext"
                android:textSize="12sp" />

            <TextView
                android:id="@+id/status"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:singleLine="true"
                android:textColor="@color/widget_subtext"
                android:textSize="11sp"
                android:visibility="gone" />
        </LinearLayout>

        <ImageView
            android:id="@+id/update"
            style="@style/UpdateSymbol" />

        <ImageView
            android:id="@+id/refresh"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:layout_marginStart="2dp"
            android:contentDescription="Refresh"
            android:padding="5dp"
            android:src="@drawable/ic_refresh" />

        <ImageView
            android:id="@+id/add"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:layout_marginStart="2dp"
            android:background="@drawable/add_bg"
            android:contentDescription="Add task"
            android:padding="4dp"
            android:src="@drawable/ic_add" />
    </LinearLayout>
'''

COLLECTION = {
    'list': '''
        <ListView
            android:id="@+id/list"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:divider="@null"
            android:dividerHeight="0dp"
            android:scrollbars="none" />
''',
    'grid': '''
        <GridView
            android:id="@+id/list"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:horizontalSpacing="10dp"
            android:numColumns="2"
            android:scrollbars="none"
            android:stretchMode="columnWidth" />
''',
}


def list_layout(kind, comment):
    return H + f'''<!-- {comment} -->
<LinearLayout{NS}
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:orientation="vertical"
    android:paddingStart="10dp"
    android:paddingTop="6dp"
    android:paddingEnd="6dp"
    android:paddingBottom="4dp">
{HEADER}
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">
{COLLECTION[kind]}
        <TextView
            android:id="@+id/empty"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:padding="4dp"
            android:text="Loading…"
            android:textColor="@color/widget_subtext"
            android:textSize="12sp" />
    </FrameLayout>
</LinearLayout>
'''


TILE = H + f'''<!-- 1x1: the count (overdue in red if any) and the widget's name. Tap for the full panel. -->
<FrameLayout{NS}
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:padding="4dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:orientation="vertical">

        <TextView
            android:id="@+id/count_big"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:fontFamily="sans-serif-medium"
            android:singleLine="true"
            android:text="–"
            android:textColor="@color/widget_text"
            android:textSize="26sp" />

        <TextView
            android:id="@+id/title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:singleLine="true"
            android:text="Todoist"
            android:textColor="@color/widget_subtext"
            android:textSize="11sp" />
    </LinearLayout>

    <ImageView
        android:id="@+id/update"
        style="@style/UpdateSymbol"
        android:layout_gravity="top|end" />
</FrameLayout>
'''

CHECK = '''
    <ImageView
        android:id="@+id/check"
        android:layout_width="24dp"
        android:layout_height="22dp"
        android:layout_marginEnd="4dp"
        android:contentDescription="Complete"
        android:paddingStart="1dp"
        android:paddingTop="3dp"
        android:paddingEnd="7dp"
        android:paddingBottom="3dp"
        android:src="@drawable/check_p4" />
'''

CONTENT = '''
        <TextView
            android:id="@+id/content"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:ellipsize="end"
            android:singleLine="true"
            android:textColor="@color/widget_text"
            android:textSize="13sp" />

        <TextView
            android:id="@+id/due"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="6dp"
            android:singleLine="true"
            android:textColor="@color/widget_subtext"
            android:textSize="11sp" />
'''

ROW_COMPACT = H + f'''<!-- One task, one line: priority circle (tap to complete), task, due. -->
<LinearLayout{NS}
    android:id="@+id/row"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="24dp"
    android:orientation="horizontal"
    android:paddingTop="1dp"
    android:paddingBottom="1dp">
{CHECK}
    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:gravity="center_vertical"
        android:orientation="horizontal">
{CONTENT}    </LinearLayout>
</LinearLayout>
'''

ROW_DETAIL = H + f'''<!-- One task, two lines: task + due, then project · labels · deadline · description. -->
<LinearLayout{NS}
    android:id="@+id/row"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="top"
    android:orientation="horizontal"
    android:paddingTop="3dp"
    android:paddingBottom="3dp">
{CHECK}
    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:minHeight="22dp"
            android:orientation="horizontal">
{CONTENT}        </LinearLayout>

        <TextView
            android:id="@+id/meta"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:singleLine="true"
            android:textColor="@color/widget_subtext"
            android:textSize="11sp"
            android:visibility="gone" />
    </LinearLayout>
</LinearLayout>
'''

ROW_MORE = H + f'''<!-- Last row when the list is capped: "+12 more" (opens the list in Todoist). -->
<TextView{NS}
    android:id="@+id/more"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center"
    android:paddingTop="6dp"
    android:paddingBottom="6dp"
    android:singleLine="true"
    android:textColor="@color/accent_text"
    android:textSize="12sp" />
'''


def write(name, text):
    with open(os.path.join(OUT, name), 'w') as f:
        f.write(text)


os.makedirs(OUT, exist_ok=True)
write('widget_tile.xml', TILE)
write('widget_list.xml', list_layout('list', 'Header + scrolling task list (every size from 2x1 up, and the panel).'))
write('widget_grid.xml', list_layout('grid', 'Header + two-column task list (wide strips, 4x1).'))
write('row_compact.xml', ROW_COMPACT)
write('row_detail.xml', ROW_DETAIL)
write('row_more.xml', ROW_MORE)
print('ok')
