/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ch_linghu.fanfoudroid;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import com.ch_linghu.fanfoudroid.data.Tweet;
import com.ch_linghu.fanfoudroid.db.StatusTable;
import com.ch_linghu.fanfoudroid.fanfou.Paging;
import com.ch_linghu.fanfoudroid.fanfou.Status;
import com.ch_linghu.fanfoudroid.http.HttpException;
import com.ch_linghu.fanfoudroid.task.GenericTask;
import com.ch_linghu.fanfoudroid.task.TaskAdapter;
import com.ch_linghu.fanfoudroid.task.TaskListener;
import com.ch_linghu.fanfoudroid.task.TaskParams;
import com.ch_linghu.fanfoudroid.task.TaskResult;
import com.ch_linghu.fanfoudroid.task.TweetCommonTask;
import com.ch_linghu.fanfoudroid.ui.base.TwitterCursorBaseActivity;

public class TwitterActivity extends TwitterCursorBaseActivity {
    private static final String TAG = "TwitterActivity";

    private static final String LAUNCH_ACTION = "com.ch_linghu.fanfoudroid.TWEETS";
    protected GenericTask mDeleteTask;

    private TaskListener mDeleteTaskListener = new TaskAdapter() {

        @Override
        public String getName() {
            return "DeleteTask";
        }

        @Override
        public void onPostExecute(GenericTask task, TaskResult result) {
            if (result == TaskResult.AUTH_ERROR) {
                logout();
            } else if (result == TaskResult.OK) {
                onDeleteSuccess();
            } else if (result == TaskResult.IO_ERROR) {
                onDeleteFailure();
            }
        }
    };

    static final int DIALOG_WRITE_ID = 0;

    public static Intent createIntent(Context context) {
        Intent intent = new Intent(LAUNCH_ACTION);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        return intent;
    }

    public static Intent createNewTaskIntent(Context context) {
        Intent intent = createIntent(context);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return intent;
    }

    @Override
    protected boolean _onCreate(Bundle savedInstanceState) {
        if (super._onCreate(savedInstanceState)) {
            mNavbar.setHeaderTitle("饭否fanfou.com");
            registerGestureListener();
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        // TODO Auto-generated method stub
        super.onDestroy();

        if (mDeleteTask != null
                && mDeleteTask.getStatus() == GenericTask.Status.RUNNING) {
            mDeleteTask.cancel(true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mDeleteTask != null
                && mDeleteTask.getStatus() == GenericTask.Status.RUNNING) {
            outState.putBoolean(SIS_RUNNING_KEY, true);
        }
    }

    // Menu.
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return super.onCreateOptionsMenu(menu);
    }

    private int CONTEXT_DELETE_ID = getLastContextMenuId() + 1;

    @Override
    protected int getLastContextMenuId() {
        return CONTEXT_DELETE_ID;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterView.AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        Tweet tweet = getContextItemTweet(info.position);
        if (null != tweet) {// 当按钮为 刷新/更多的时候为空

            if (tweet.userId.equals(TwitterApplication.getMyselfId())) {
                menu.add(0, CONTEXT_DELETE_ID, 0, R.string.cmenu_delete);
            }

        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
                .getMenuInfo();
        Tweet tweet = getContextItemTweet(info.position);

        if (tweet == null) {
            Log.w(TAG, "Selected item not available.");
            return super.onContextItemSelected(item);
        }

        if (item.getItemId() == CONTEXT_DELETE_ID) {
            doDelete(tweet.id);
            return true;
        } else {
            return super.onContextItemSelected(item);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected Cursor fetchMessages() {
        return getDb().fetchAllTweets(getUserId(), StatusTable.TYPE_HOME);
    }

    @Override
    protected String getActivityTitle() {
        return getResources().getString(R.string.page_title_home);
    }

    @Override
    protected void markAllRead() {
        getDb().markAllTweetsRead(getUserId(), StatusTable.TYPE_HOME);
    }

    // hasRetrieveListTask interface
    @Override
    public int addMessages(ArrayList<Tweet> tweets, boolean isUnread) {
        // 获取消息的时候，将status里获取的user也存储到数据库

        // ::MARK::
        for (Tweet t : tweets) {
            getDb().createWeiboUserInfo(t.user);
        }
        return getDb().putTweets(tweets, getUserId(), StatusTable.TYPE_HOME,
                isUnread);
    }

    @Override
    public String fetchMaxId() {
        return getDb().fetchMaxTweetId(getUserId(), StatusTable.TYPE_HOME);
    }

    @Override
    public List<Status> getMessageSinceId(String maxId) throws HttpException {
        if (maxId != null) {
            return getApi().getFriendsTimeline(new Paging(maxId));
        } else {
            return getApi().getFriendsTimeline();
        }
    }

    public void onDeleteFailure() {
        Log.e(TAG, "Delete failed");
    }

    public void onDeleteSuccess() {
        mTweetAdapter.refresh();
    }

    private void doDelete(String id) {

        if (mDeleteTask != null
                && mDeleteTask.getStatus() == GenericTask.Status.RUNNING) {
            return;
        } else {
            mDeleteTask = new TweetCommonTask.DeleteTask(this);
            mDeleteTask.setListener(mDeleteTaskListener);

            TaskParams params = new TaskParams();
            params.put("id", id);
            mDeleteTask.execute(params);
        }
    }

    @Override
    public String fetchMinId() {
        return getDb().fetchMinTweetId(getUserId(), StatusTable.TYPE_HOME);
    }

    @Override
    public List<Status> getMoreMessageFromId(String minId) throws HttpException {
        Paging paging = new Paging(1, 20);
        paging.setMaxId(minId);
        return getApi().getFriendsTimeline(paging);
    }

    @Override
    public int getDatabaseType() {
        return StatusTable.TYPE_HOME;
    }

    @Override
    public String getUserId() {
        return TwitterApplication.getMyselfId();
    }

    // ////////////////// Gesture test /////////////////////////////////////
    private static boolean USE_GESTRUE = true;
    protected MyGestureListener myGestureListener = null;
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // or implement in activity or component. When your not assigning to a
        // child component.
        if (myGestureListener != null) {
            return myGestureListener.getDetector().onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    // use it in _onCreate
    private void registerGestureListener() {
        if (USE_GESTRUE) {
            myGestureListener = new MyGestureListener(this);
            // or if you have already created a Gesture Detector.
            // myGestureListener = new MyGestureListener(this,
            // getExistingGestureDetector());

            // Example of setting listener. The onTouchEvent will now be called on
            // your listener
            getTweetList().setOnTouchListener(myGestureListener);
        }
    }

    private class MyGestureListener extends SimpleOnGestureListener implements
            OnTouchListener {
        private static final int SWIPE_MIN_DISTANCE = 120;
        private static final int SWIPE_MAX_OFF_PATH = 250;
        private static final int SWIPE_THRESHOLD_VELOCITY = 200;

        GestureDetector gDetector;
        
        public MyGestureListener(Context context) {
            this(context, null);
        }

        public MyGestureListener(Context context, GestureDetector gDetector) {
            if (gDetector == null) {
                gDetector = new GestureDetector(context, this);
            }
            this.gDetector = gDetector;
        }
        
        private void gotoActivity(Class<?> cls) {
            Intent intent = new Intent();
            intent.setClass(TwitterActivity.this, cls);
            TwitterActivity.this.startActivity(intent);
            
            TwitterActivity.this.finish(); // For DEBUG
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                float velocityY) {
            Log.d("FLING", "On fling");
            boolean orig = super.onFling(e1, e2, velocityX, velocityY);

            try {
                if (Math.abs(e1.getY() - e2.getY()) > SWIPE_MAX_OFF_PATH) {
                    return orig;
                }
                if (e1.getX() - e2.getX() > SWIPE_MIN_DISTANCE
                        && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                    Log.d("FLING", "<------");
                    gotoActivity(DmActivity.class);
                    return true;
                } else if (e2.getX() - e1.getX() > SWIPE_MIN_DISTANCE
                        && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                    Log.d("FLING", "------>");
                    gotoActivity(MentionActivity.class);
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }

            return orig;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            // TODO Auto-generated method stub
            super.onLongPress(e);
        }

        /*
         * @Override public boolean onSingleTapConfirmed(MotionEvent e) {
         * 
         * return super.onSingleTapConfirmed(e); }
         */
        
        public boolean onTouch(View v, MotionEvent event) {
            // Log.d("FLING", "On Touch");

            // Within the MyGestureListener class you can now manage the
            // event.getAction() codes.

            // Note that we are now calling the gesture Detectors onTouchEvent.
            // And given we've set this class as the GestureDetectors listener
            // the onFling, onSingleTap etc methods will be executed.
            return gDetector.onTouchEvent(event);
        }

        public GestureDetector getDetector() {
            return gDetector;
        }
    }
}