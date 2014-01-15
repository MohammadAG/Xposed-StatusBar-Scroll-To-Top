package com.mohammadag.statusbarscrolltotop;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

import java.util.ArrayList;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ScrollView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedMod implements IXposedHookLoadPackage, IXposedHookZygoteInit {
	/* My name's here so we don't conflict with other fields, deal with it :p */
	private static final String KEY_RECEIVERS = "mMohammadAG_scrolToTopReceivers";

	/* You can trigger this with any app that can fire intents! */
	private static final String INTENT_SCROLL_TO_TOP = "com.mohammadag.statusbarscrolltotop.SCROLL_TO_TOP";

	/* We get a MotionEvent when the status bar is tapped, we need to know if it's a click or a drag */
	private float mDownX;
	private float mDownY;
	private final float SCROLL_THRESHOLD = 10;
	private boolean mIsClick;

	/* Some sort of crappy IPC */
	class ScrollViewReceiver extends BroadcastReceiver {
		private ScrollView mScrollView;
		public ScrollViewReceiver(ScrollView view) {
			mScrollView = view;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if (isViewInViewBounds(getContentViewFromContext(mScrollView.getContext()), mScrollView))
				mScrollView.smoothScrollTo(0, 0);
		}
	};

	class AbsListViewReceiver extends BroadcastReceiver {
		private AbsListView mListView;
		public AbsListViewReceiver(AbsListView view) {
			mListView = view;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if (isViewInViewBounds(getContentViewFromContext(mListView.getContext()), mListView))
				mListView.smoothScrollToPosition(0);
		}
	}

	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		/* AbsListView, it's one instance of a scroller */
		findAndHookMethod(AbsListView.class, "initAbsListView", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				AbsListView view = (AbsListView) param.thisObject;
				if (!(view.getContext() instanceof Activity))
					return;
				Activity activity = (Activity) view.getContext();		
				AbsListViewReceiver receiver = new AbsListViewReceiver(view);
				addReceiverToActivity(activity, receiver);
				activity.registerReceiver(receiver, new IntentFilter(INTENT_SCROLL_TO_TOP));
			}
		});

		/* Another one */
		findAndHookMethod(ScrollView.class, "initScrollView", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				ScrollView view = (ScrollView) param.thisObject;
				if (!(view.getContext() instanceof Activity))
					return;
				Activity activity = (Activity) view.getContext();		
				ScrollViewReceiver receiver = new ScrollViewReceiver(view);
				addReceiverToActivity(activity, receiver);
				activity.registerReceiver(receiver, new IntentFilter(INTENT_SCROLL_TO_TOP));
			}
		});

		/* FYI, there are some manufacturer specific ones, like Samsung's TouchWiz ones.
		 * I'll look into those later on...
		 */

		/* We need to register and unregister receivers in onPause and onResume 
		 * otherwise, Android bitches about it (for good (memory) reason(s) probably). We do that
		 * by keeping an ArrayList of BroadcastReceivers in all activities. There might be a better
		 * way...
		 */
		Class<?> ActivityClass = findClass("android.app.Activity", null);
		findAndHookMethod(ActivityClass, "onResume", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				Activity activity = (Activity) param.thisObject;
				resumeBroadcastReceivers(activity);
			}
		});

		findAndHookMethod(ActivityClass, "onPause", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				Activity activity = (Activity) param.thisObject;
				pauseBroadcastReceivers(activity);
			}
		});
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.android.systemui"))
			return;

		Class<?> StatusBarWindowView = findClass("com.android.systemui.statusbar.phone.StatusBarWindowView",
				lpparam.classLoader);

		findAndHookMethod(StatusBarWindowView, "onInterceptTouchEvent", MotionEvent.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				MotionEvent ev = (MotionEvent) param.args[0];
				View view = (View) param.thisObject;
				switch (ev.getAction() & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_DOWN:
					mDownX = ev.getX();
					mDownY = ev.getY();
					mIsClick = true;
					break;
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP:
					if (mIsClick) {
						try {
							/* Get NotificationPanelView instance, it subclasses PanelView */
							Object notificationPanelView =
									XposedHelpers.getObjectField(param.thisObject, "mNotificationPanel");

							float expandedFraction = (Float) XposedHelpers.callMethod(notificationPanelView,
									"getExpandedFraction");

							if (expandedFraction < 0.1)
								view.getContext().sendBroadcast(new Intent(INTENT_SCROLL_TO_TOP));
						} catch (Throwable t) {
							XposedBridge.log("StatusBarScrollToTop: Unable to determine expanded fraction: " + t.getMessage());
							t.printStackTrace();
							view.getContext().sendBroadcast(new Intent(INTENT_SCROLL_TO_TOP));
						}
					}
					break;
				case MotionEvent.ACTION_MOVE:
					if (mIsClick && (Math.abs(mDownX - ev.getX()) > SCROLL_THRESHOLD || Math.abs(mDownY - ev.getY()) > SCROLL_THRESHOLD)) {
						mIsClick = false;
					}
					break;
				default:
					break;
				}
			}
		});
	}

	/* Helpers so the code looks less like shit */
	private static void addReceiverToActivity(Activity activity, BroadcastReceiver receiver) {
		ArrayList<BroadcastReceiver> receivers = getReceiversForActivity(activity);
		receivers.add(receiver);
		XposedHelpers.setAdditionalInstanceField(activity, KEY_RECEIVERS, receivers);
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<BroadcastReceiver> getReceiversForActivity(Activity activity) {
		ArrayList<BroadcastReceiver> receivers = null;
		try {
			receivers = (ArrayList<BroadcastReceiver>) XposedHelpers.getAdditionalInstanceField(activity,
					KEY_RECEIVERS);
		} catch (Throwable t) {
			t.printStackTrace();
		}

		if (receivers == null) {
			receivers = new ArrayList<BroadcastReceiver>();
		}

		return receivers;
	}

	private static void resumeBroadcastReceivers(Activity activity) {
		ArrayList<BroadcastReceiver> receivers = getReceiversForActivity(activity);
		for (BroadcastReceiver receiver : receivers) {
			activity.registerReceiver(receiver, new IntentFilter(INTENT_SCROLL_TO_TOP));
		}
	}

	private static void pauseBroadcastReceivers(Activity activity) {
		ArrayList<BroadcastReceiver> receivers = getReceiversForActivity(activity);
		for (BroadcastReceiver receiver : receivers) {
			activity.unregisterReceiver(receiver);
		}
	}

	/* Check if the View is visible to the user, i.e on screen.
	 * We do this since in tabbed interfaces, we can cause a scrollbar that's off screen
	 * to go to the top as well as the visible one.
	 */
	private static boolean isViewInViewBounds(View mainView, View view) {
		/* Failsafe */
		if (mainView == null)
			return true;

		Rect bounds = new Rect();
		mainView.getHitRect(bounds);
		return view.getLocalVisibleRect(bounds);
	}

	/* This works because Context is actually Activity downcasted */
	private static View getContentViewFromContext(Context context) {
		if (!(context instanceof Activity))
			return null;

		return ((Activity) context).findViewById(android.R.id.content);
	}

	/* And that's a wrap */
}
