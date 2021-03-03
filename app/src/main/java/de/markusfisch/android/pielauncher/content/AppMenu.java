package de.markusfisch.android.pielauncher.content;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.provider.CalendarContract;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.markusfisch.android.pielauncher.graphics.CanvasPieMenu;
import de.markusfisch.android.pielauncher.graphics.Converter;

public class AppMenu extends CanvasPieMenu {
	public static class AppIcon extends CanvasPieMenu.CanvasIcon {
		public final Rect hitRect = new Rect();
		public final ComponentName componentName;
		public final String label;

		AppIcon(ComponentName componentName, String label, Drawable icon) {
			super(Converter.getBitmapFromDrawable(icon));
			this.componentName = componentName;
			this.label = label;
		}
	}

	public interface UpdateListener {
		void onUpdate();
	}

	private static final boolean HAS_LAUNCHER_APP =
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
	private static final String MENU = "menu";

	private final HashMap<ComponentName, AppIcon> apps = new HashMap<>();
	private final Comparator<AppIcon> appLabelComparator = new Comparator<AppIcon>() {
		public int compare(AppIcon left, AppIcon right) {
			// Fast enough to do it for every comparison.
			// Otherwise, if defaultLocale was a permanent field outside
			// this scope, we'd need to listen for configuration changes
			// because the locale may change.
			Locale defaultLocale = Locale.getDefault();
			// compareToIgnoreCase() does not take locale into account.
			return left.label.toLowerCase(defaultLocale).compareTo(
					right.label.toLowerCase(defaultLocale));
		}
	};

	private UpdateListener updateListener;
	private UserHandle userHandle;
	private LauncherApps launcherApps;
	private boolean indexing = false;

	public boolean launchSelectedApp(Context context) {
		int selectedIcon = getSelectedIcon();
		if (selectedIcon > -1) {
			launchApp(context, ((AppIcon) icons.get(selectedIcon)));
			return true;
		}
		return false;
	}

	public void launchApp(Context context, AppIcon icon) {
		if (HAS_LAUNCHER_APP) {
			launcherApps.startMainActivity(
					icon.componentName,
					userHandle,
					icon.rect,
					null);
		} else {
			PackageManager pm = context.getPackageManager();
			Intent intent;
			if (pm == null || (intent = pm.getLaunchIntentForPackage(
					icon.componentName.getPackageName())) == null) {
				return;
			}
			context.startActivity(intent);
		}
	}

	public void setUpdateListener(UpdateListener listener) {
		updateListener = listener;
	}

	public void store(Context context) {
		storeMenu(context, icons);
	}

	public synchronized List<AppIcon> filterAppsBy(String query) {
		if (query == null) {
			query = "";
		}
		query = query.trim().toLowerCase(Locale.getDefault());
		ArrayList<AppIcon> list = new ArrayList<>();
		ArrayList<AppIcon> contain = new ArrayList<>();
		ArrayList<AppIcon> hamming = new ArrayList<>();
		if (query.length() < 1) {
			list.addAll(apps.values());
		} else {
			for (Map.Entry<ComponentName, AppIcon> entry : apps.entrySet()) {
				AppIcon appIcon = entry.getValue();
				String label = appIcon.label.toLowerCase(Locale.getDefault());
				if (label.startsWith(query)) {
					list.add(appIcon);
				} else if (label.contains(query)) {
					contain.add(appIcon);
				} else if (hammingDistance(label, query) < 2) {
					hamming.add(appIcon);
				}
			}
		}
		Collections.sort(list, appLabelComparator);
		Collections.sort(contain, appLabelComparator);
		Collections.sort(hamming, appLabelComparator);
		list.addAll(contain);
		list.addAll(hamming);
		return list;
	}

	// This AsyncTask is running for a short and finite time only
	// and it's perfectly okay to delay garbage collection of the
	// parent instance until this task has been terminated.
	@SuppressLint("StaticFieldLeak")
	public void removePackageAsync(final String packageName) {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... nothing) {
				removePackage(packageName);
				return null;
			}

			@Override
			protected void onPostExecute(Void nothing) {
				if (updateListener != null) {
					updateListener.onUpdate();
				}
			}
		}.execute();
	}

	public boolean isEmpty() {
		return apps.isEmpty();
	}

	public boolean isIndexing() {
		return indexing;
	}

	public void indexAppsAsync(Context context) {
		indexAppsAsync(context, null);
	}

	// This AsyncTask is running for a short and finite time only
	// and it's perfectly okay to delay garbage collection of the
	// parent instance until this task has been terminated.
	@SuppressLint("StaticFieldLeak")
	public void indexAppsAsync(Context context,
			final String packageNameRestriction) {
		// Get application context to not block garbage collection
		// on other Context objects.
		final Context appContext = context.getApplicationContext();
		if (HAS_LAUNCHER_APP) {
			userHandle = Process.myUserHandle();
			launcherApps = (LauncherApps) appContext.getSystemService(
					Context.LAUNCHER_APPS_SERVICE);
		}
		indexing = true;
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... nothing) {
				indexApps(appContext, packageNameRestriction);
				return null;
			}

			@Override
			protected void onPostExecute(Void nothing) {
				indexing = false;
				if (updateListener != null) {
					updateListener.onUpdate();
				}
			}
		}.execute();
	}

	private synchronized void indexApps(Context context,
			String packageNameRestriction) {
		Intent intent = new Intent(Intent.ACTION_MAIN, null);
		intent.addCategory(Intent.CATEGORY_LAUNCHER);
		if (packageNameRestriction != null) {
			// Remove old package and add it anew.
			removePackageFromApps(packageNameRestriction);
			// Don't call removePackageFromPieMenu() here because the
			// icon will be updated anyway by createIcons() below.
			intent.setPackage(packageNameRestriction);
		} else {
			apps.clear();
		}
		PackageManager pm = context.getPackageManager();
		List<ResolveInfo> activities = pm.queryIntentActivities(intent, 0);
		String skip = context.getPackageName();
		for (ResolveInfo info : activities) {
			String packageName = info.activityInfo.applicationInfo.packageName;
			if (skip.equals(packageName)) {
				// Always skip this package.
				continue;
			}
			if (HAS_LAUNCHER_APP) {
				for (LauncherActivityInfo ai : launcherApps.getActivityList(
						packageName, userHandle)) {
					addApp(ai.getComponentName(),
							ai.getLabel().toString(),
							ai.getBadgedIcon(0));
				}
			} else {
				addApp(getComponentName(info.activityInfo),
						info.loadLabel(pm).toString(),
						info.loadIcon(pm));
			}
		}
		// Always reload icons because drawables may have changed.
		createIcons(context);
	}

	private void addApp(ComponentName componentName, String label,
			Drawable icon) {
		apps.put(componentName, new AppIcon(componentName, label, icon));
	}

	private void createIcons(Context context) {
		icons.clear();
		icons.addAll(restoreMenu(context, apps));
		if (icons.isEmpty()) {
			createInitialMenu(context.getPackageManager());
		}
	}

	private Intent getCalendarIntent() {
		Intent intent = new Intent(Intent.ACTION_EDIT)
				.setType("vnd.android.cursor.item/event");
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
			return intent
					.putExtra("title", "dummy")
					.putExtra("beginTime", 0)
					.putExtra("endTime", 0);
		} else {
			return intent
					.putExtra(CalendarContract.Events.TITLE, "dummy")
					.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0)
					.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0);
		}
	}

	private void createInitialMenu(PackageManager pm) {
		Intent[] intents = new Intent[]{
				new Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
				new Intent(Intent.ACTION_DIAL),
				new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")),
				new Intent(Intent.ACTION_PICK,
						android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
				new Intent(Intent.ACTION_VIEW, Uri.parse("geo:47.6,-122.3")),
				new Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=46.414382,10.013988"))
						.setPackage("com.google.android.apps.maps"),
				getCalendarIntent(),
				new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
				new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
		};
		ArrayList<String> defaults = new ArrayList<>();
		for (Intent intent : intents) {
			String packageName = resolveDefaultAppForIntent(pm, intent);
			if (packageName == null || defaults.contains(packageName)) {
				continue;
			}
			// Get launch intent because the class name from above intents
			// doesn't match the launch intent and so doesn't match the
			// ComponentName key in apps.
			Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
			if (launchIntent == null) {
				continue;
			}
			AppIcon appIcon = apps.get(launchIntent.getComponent());
			if (appIcon != null) {
				defaults.add(packageName);
				addAppIcon(appIcon);
			}
		}
		int max = Math.min(apps.size(), 8);
		int i = icons.size();
		for (Map.Entry<ComponentName, AppIcon> entry : apps.entrySet()) {
			if (i >= max) {
				break;
			}
			if (!defaults.contains(entry.getKey().getPackageName())) {
				addAppIcon(entry.getValue());
				++i;
			}
		}
	}

	private static String resolveDefaultAppForIntent(PackageManager pm,
			Intent intent) {
		ResolveInfo resolveInfo = pm.resolveActivity(intent,
				PackageManager.MATCH_DEFAULT_ONLY);
		return resolveInfo != null ?
				resolveInfo.activityInfo.packageName : null;
	}

	private void addAppIcon(AppIcon appIcon) {
		if (appIcon != null) {
			icons.add(appIcon);
		}
	}

	private static ComponentName getComponentName(ActivityInfo info) {
		return new ComponentName(info.packageName, info.name);
	}

	private void removePackage(String packageName) {
		removePackageFromApps(packageName);
		removePackageFromPieMenu(packageName);
	}

	private synchronized void removePackageFromApps(String packageName) {
		Iterator<Map.Entry<ComponentName, AppIcon>> it =
				apps.entrySet().iterator();
		while (it.hasNext()) {
			if (packageName.equals((it.next().getValue())
					.componentName.getPackageName())) {
				it.remove();
			}
		}
	}

	private synchronized void removePackageFromPieMenu(String packageName) {
		Iterator<Icon> it = icons.iterator();
		while (it.hasNext()) {
			if (packageName.equals(((AppIcon) it.next())
					.componentName.getPackageName())) {
				it.remove();
			}
		}
	}

	private static List<Icon> restoreMenu(Context context,
			HashMap<ComponentName, AppIcon> apps) {
		ArrayList<Icon> icons = new ArrayList<>();
		try {
			for (String line : readLines(context.openFileInput(MENU))) {
				Icon icon = apps.get(ComponentName.unflattenFromString(line));
				if (icon != null) {
					icons.add(icon);
				}
			}
		} catch (FileNotFoundException e) {
			// Return an empty array.
		}
		return icons;
	}

	private static List<String> readLines(InputStream is) {
		ArrayList<String> list = new ArrayList<>();
		BufferedReader reader = null;
		// It's not possible to use automatic resource management
		// for this statement because it requires minSDK 19.
		try {
			// StandardCharsets.UTF_8 cannot be used because it requires
			// minSDK 19.
			reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			while (reader.ready()) {
				list.add(reader.readLine());
			}
		} catch (IOException e) {
			// Return what we got so far.
		} finally {
			try {
				if (reader != null) {
					reader.close();
				}
			} catch (IOException e) {
				// Ignore, can't do anything about it.
			}
		}
		return list;
	}

	private static boolean storeMenu(Context context, List<Icon> icons) {
		ArrayList<String> items = new ArrayList<>();
		for (CanvasPieMenu.Icon icon : icons) {
			items.add(((AppIcon) icon).componentName.flattenToString());
		}
		try {
			return writeLines(context.openFileOutput(MENU,
					Context.MODE_PRIVATE), items);
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean writeLines(OutputStream os, List<String> lines) {
		if (os == null) {
			return false;
		}
		try {
			// StandardCharsets.UTF_8 cannot be used because it requires
			// minSDK 19.
			byte[] lf = "\n".getBytes("UTF-8");
			for (String line : lines) {
				os.write(line.getBytes("UTF-8"));
				os.write(lf);
			}
			return true;
		} catch (IOException e) {
			return false;
		} finally {
			try {
				os.close();
			} catch (IOException e) {
				// Ignore, can't do anything about it.
			}
		}
	}

	private static int hammingDistance(String a, String b) {
		int count = 0;
		for (int i = 0, l = Math.min(a.length(), b.length()); i < l; ++i) {
			if (a.charAt(i) != b.charAt(i)) {
				++count;
			}
		}
		return count;
	}
}
