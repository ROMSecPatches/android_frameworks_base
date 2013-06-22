/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils.TruncateAt;
import android.util.IntProperty;
import android.util.MathUtils;
import android.util.Property;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroupOverlay;
import android.widget.AbsListView.OnScrollListener;

import com.android.internal.R;

/**
 * Helper class for AbsListView to draw and control the Fast Scroll thumb
 */
class FastScroller {
    /** Duration of fade-out animation. */
    private static final int DURATION_FADE_OUT = 300;

    /** Duration of fade-in animation. */
    private static final int DURATION_FADE_IN = 150;

    /** Duration of transition cross-fade animation. */
    private static final int DURATION_CROSS_FADE = 50;

    /** Duration of transition resize animation. */
    private static final int DURATION_RESIZE = 100;

    /** Inactivity timeout before fading controls. */
    private static final long FADE_TIMEOUT = 1500;

    /** Minimum number of pages to justify showing a fast scroll thumb. */
    private static final int MIN_PAGES = 4;

    /** Scroll thumb and preview not showing. */
    private static final int STATE_NONE = 0;

    /** Scroll thumb visible and moving along with the scrollbar. */
    private static final int STATE_VISIBLE = 1;

    /** Scroll thumb and preview being dragged by user. */
    private static final int STATE_DRAGGING = 2;

    /** Styleable attributes. */
    private static final int[] ATTRS = new int[] {
        android.R.attr.fastScrollTextColor,
        android.R.attr.fastScrollThumbDrawable,
        android.R.attr.fastScrollTrackDrawable,
        android.R.attr.fastScrollPreviewBackgroundLeft,
        android.R.attr.fastScrollPreviewBackgroundRight,
        android.R.attr.fastScrollOverlayPosition
    };

    // Styleable attribute indices.
    private static final int TEXT_COLOR = 0;
    private static final int THUMB_DRAWABLE = 1;
    private static final int TRACK_DRAWABLE = 2;
    private static final int PREVIEW_BACKGROUND_LEFT = 3;
    private static final int PREVIEW_BACKGROUND_RIGHT = 4;
    private static final int OVERLAY_POSITION = 5;

    // Positions for preview image and text.
    private static final int OVERLAY_FLOATING = 0;
    private static final int OVERLAY_AT_THUMB = 1;

    // Indices for mPreviewResId.
    private static final int PREVIEW_LEFT = 0;
    private static final int PREVIEW_RIGHT = 1;

    /** Delay before considering a tap in the thumb area to be a drag. */
    private static final long TAP_TIMEOUT = ViewConfiguration.getTapTimeout();

    private final Rect mTempBounds = new Rect();
    private final Rect mTempMargins = new Rect();

    private final AbsListView mList;
    private final ViewGroupOverlay mOverlay;
    private final TextView mPrimaryText;
    private final TextView mSecondaryText;
    private final ImageView mThumbImage;
    private final ImageView mTrackImage;
    private final ImageView mPreviewImage;

    /**
     * Preview image resource IDs for left- and right-aligned layouts. See
     * {@link #PREVIEW_LEFT} and {@link #PREVIEW_RIGHT}.
     */
    private final int[] mPreviewResId = new int[2];

    /**
     * Padding in pixels around the preview text. Applied as layout margins to
     * the preview text and padding to the preview image.
     */
    private final int mPreviewPadding;

    /** Whether there is a track image to display. */
    private final boolean mHasTrackImage;

    /** Set containing decoration transition animations. */
    private AnimatorSet mDecorAnimation;

    /** Set containing preview text transition animations. */
    private AnimatorSet mPreviewAnimation;

    /** Whether the primary text is showing. */
    private boolean mShowingPrimary;

    /** Whether we're waiting for completion of scrollTo(). */
    private boolean mScrollCompleted;

    /** The position of the first visible item in the list. */
    private int mFirstVisibleItem;

    /** The number of headers at the top of the view. */
    private int mHeaderCount;

    /** The number of items in the list. */
    private int mItemCount = -1;

    /** The index of the current section. */
    private int mCurrentSection = -1;

    /** Whether the list is long enough to need a fast scroller. */
    private boolean mLongList;

    private Object[] mSections;

    /**
     * Current decoration state, one of:
     * <ul>
     * <li>{@link #STATE_NONE}, nothing visible
     * <li>{@link #STATE_VISIBLE}, showing track and thumb
     * <li>{@link #STATE_DRAGGING}, visible and showing preview
     * </ul>
     */
    private int mState;

    private BaseAdapter mListAdapter;
    private SectionIndexer mSectionIndexer;

    /** Whether decorations should be laid out from right to left. */
    private boolean mLayoutFromRight;

    /** Whether the scrollbar and decorations should always be shown. */
    private boolean mAlwaysShow;

    /**
     * Position for the preview image and text. One of:
     * <ul>
     * <li>{@link #OVERLAY_AT_THUMB}
     * <li>{@link #OVERLAY_FLOATING}
     * </ul>
     */
    private int mOverlayPosition;

    /** Whether to precisely match the thumb position to the list. */
    private boolean mMatchDragPosition;

    private float mInitialTouchY;
    private boolean mHasPendingDrag;
    private int mScaledTouchSlop;

    private final Runnable mDeferStartDrag = new Runnable() {
        @Override
        public void run() {
            if (mList.mIsAttached) {
                beginDrag();

                final float pos = getPosFromMotionEvent(mInitialTouchY);
                scrollTo(pos);
            }

            mHasPendingDrag = false;
        }
    };

    /**
     * Used to delay hiding fast scroll decorations.
     */
    private final Runnable mDeferHide = new Runnable() {
        @Override
        public void run() {
            setState(STATE_NONE);
        }
    };

    /**
     * Used to effect a transition from primary to secondary text.
     */
    private final AnimatorListener mSwitchPrimaryListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mShowingPrimary = !mShowingPrimary;
        }
    };

    public FastScroller(Context context, AbsListView listView) {
        mList = listView;
        mOverlay = listView.getOverlay();

        mScaledTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        final Resources res = context.getResources();
        final TypedArray ta = context.getTheme().obtainStyledAttributes(ATTRS);

        mTrackImage = new ImageView(context);

        // Add track to overlay if it has an image.
        final int trackResId = ta.getResourceId(TRACK_DRAWABLE, 0);
        if (trackResId != 0) {
            mHasTrackImage = true;
            mTrackImage.setBackgroundResource(trackResId);
            mOverlay.add(mTrackImage);
        } else {
            mHasTrackImage = false;
        }

        mThumbImage = new ImageView(context);

        // Add thumb to overlay if it has an image.
        final Drawable thumbDrawable = ta.getDrawable(THUMB_DRAWABLE);
        if (thumbDrawable != null) {
            mThumbImage.setImageDrawable(thumbDrawable);
            mOverlay.add(mThumbImage);
        }

        // If necessary, apply minimum thumb width and height.
        if (thumbDrawable.getIntrinsicWidth() <= 0 || thumbDrawable.getIntrinsicHeight() <= 0) {
            mThumbImage.setMinimumWidth(res.getDimensionPixelSize(R.dimen.fastscroll_thumb_width));
            mThumbImage.setMinimumHeight(
                    res.getDimensionPixelSize(R.dimen.fastscroll_thumb_height));
        }

        final int previewSize = res.getDimensionPixelSize(R.dimen.fastscroll_overlay_size);
        mPreviewImage = new ImageView(context);
        mPreviewImage.setMinimumWidth(previewSize);
        mPreviewImage.setMinimumHeight(previewSize);
        mPreviewImage.setAlpha(0f);
        mOverlay.add(mPreviewImage);

        mPreviewPadding = res.getDimensionPixelSize(R.dimen.fastscroll_overlay_padding);

        mPrimaryText = createPreviewTextView(context, ta);
        mOverlay.add(mPrimaryText);
        mSecondaryText = createPreviewTextView(context, ta);
        mOverlay.add(mSecondaryText);

        mPreviewResId[PREVIEW_LEFT] = ta.getResourceId(PREVIEW_BACKGROUND_LEFT, 0);
        mPreviewResId[PREVIEW_RIGHT] = ta.getResourceId(PREVIEW_BACKGROUND_RIGHT, 0);
        mOverlayPosition = ta.getInt(OVERLAY_POSITION, OVERLAY_FLOATING);
        ta.recycle();

        mScrollCompleted = true;
        mState = STATE_VISIBLE;
        mMatchDragPosition =
                context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.HONEYCOMB;

        getSectionsFromIndexer();
        refreshDrawablePressedState();
        setScrollbarPosition(mList.getVerticalScrollbarPosition());

        mList.postDelayed(mDeferHide, FADE_TIMEOUT);
    }

    /**
     * @param alwaysShow Whether the fast scroll thumb should always be shown
     */
    public void setAlwaysShow(boolean alwaysShow) {
        mAlwaysShow = alwaysShow;

        if (alwaysShow) {
            setState(STATE_VISIBLE);
        } else if (mState == STATE_VISIBLE) {
            mList.postDelayed(mDeferHide, FADE_TIMEOUT);
        }
    }

    /**
     * @return Whether the fast scroll thumb will always be shown
     * @see #setAlwaysShow(boolean)
     */
    public boolean isAlwaysShowEnabled() {
        return mAlwaysShow;
    }

    /**
     * Immediately transitions the fast scroller decorations to a hidden state.
     */
    public void stop() {
        setState(STATE_NONE);
    }

    /**
     * @return Whether the fast scroll thumb should be shown.
     */
    public boolean shouldShow() {
        // Don't show if the list is as tall as or shorter than the thumbnail.
        if (mList.getHeight() <= mThumbImage.getHeight()) {
            return false;
        }

        return true;
    }

    public void setScrollbarPosition(int position) {
        if (position == View.SCROLLBAR_POSITION_DEFAULT) {
            position = mList.isLayoutRtl() ?
                    View.SCROLLBAR_POSITION_LEFT : View.SCROLLBAR_POSITION_RIGHT;
        }

        mLayoutFromRight = position != View.SCROLLBAR_POSITION_LEFT;

        final int previewResId = mPreviewResId[mLayoutFromRight ? PREVIEW_RIGHT : PREVIEW_LEFT];
        mPreviewImage.setBackgroundResource(previewResId);

        // Add extra padding for text.
        final Drawable background = mPreviewImage.getBackground();
        if (background != null) {
            final Rect padding = mTempBounds;
            background.getPadding(padding);
            padding.offset(mPreviewPadding, mPreviewPadding);
            mPreviewImage.setPadding(padding.left, padding.top, padding.right, padding.bottom);
        }

        updateLayout();
    }

    public int getWidth() {
        return mThumbImage.getWidth();
    }

    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateLayout();
    }

    public void onItemCountChanged(int oldTotalItemCount, int totalItemCount) {
        final int visibleItemCount = mList.getChildCount();
        final boolean hasMoreItems = totalItemCount - visibleItemCount > 0;
        if (hasMoreItems && mState != STATE_DRAGGING) {
            final int firstVisibleItem = mList.getFirstVisiblePosition();
            setThumbPos(getPosFromItemCount(firstVisibleItem, visibleItemCount, totalItemCount));
        }
    }

    /**
     * Creates a view into which preview text can be placed.
     */
    private TextView createPreviewTextView(Context context, TypedArray ta) {
        final LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        final Resources res = context.getResources();
        final int minSize = res.getDimensionPixelSize(R.dimen.fastscroll_overlay_size);
        final ColorStateList textColor = ta.getColorStateList(TEXT_COLOR);
        final float textSize = res.getDimension(R.dimen.fastscroll_overlay_text_size);
        final TextView textView = new TextView(context);
        textView.setLayoutParams(params);
        textView.setTextColor(textColor);
        textView.setTextSize(textSize);
        textView.setSingleLine(true);
        textView.setEllipsize(TruncateAt.MIDDLE);
        textView.setGravity(Gravity.CENTER);
        textView.setAlpha(0f);

        // Manually propagate inherited layout direction.
        textView.setLayoutDirection(mList.getLayoutDirection());

        return textView;
    }

    /**
     * Measures and layouts the scrollbar and decorations.
     */
    private void updateLayout() {
        layoutThumb();
        layoutTrack();

        final Rect bounds = mTempBounds;
        measurePreview(mPrimaryText, bounds);
        applyLayout(mPrimaryText, bounds);
        measurePreview(mSecondaryText, bounds);
        applyLayout(mSecondaryText, bounds);

        if (mPreviewImage != null) {
            // Apply preview image padding.
            bounds.left -= mPreviewImage.getPaddingLeft();
            bounds.top -= mPreviewImage.getPaddingTop();
            bounds.right += mPreviewImage.getPaddingRight();
            bounds.bottom += mPreviewImage.getPaddingBottom();
            applyLayout(mPreviewImage, bounds);
        }
    }

    /**
     * Layouts a view within the specified bounds and pins the pivot point to
     * the appropriate edge.
     *
     * @param view The view to layout.
     * @param bounds Bounds at which to layout the view.
     */
    private void applyLayout(View view, Rect bounds) {
        view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
        view.setPivotX(mLayoutFromRight ? bounds.right - bounds.left : 0);
    }

    /**
     * Measures the preview text bounds, taking preview image padding into
     * account. This method should only be called after {@link #layoutThumb()}
     * and {@link #layoutTrack()} have both been called at least once.
     *
     * @param v The preview text view to measure.
     * @param out Rectangle into which measured bounds are placed.
     */
    private void measurePreview(View v, Rect out) {
        // Apply the preview image's padding as layout margins.
        final Rect margins = mTempMargins;
        margins.left = mPreviewImage.getPaddingLeft();
        margins.top = mPreviewImage.getPaddingTop();
        margins.right = mPreviewImage.getPaddingRight();
        margins.bottom = mPreviewImage.getPaddingBottom();

        if (mOverlayPosition == OVERLAY_AT_THUMB) {
            measureViewToSide(v, mThumbImage, margins, out);
        } else {
            measureFloating(v, margins, out);
        }
    }

    /**
     * Measures the bounds for a view that should be laid out against the edge
     * of an adjacent view. If no adjacent view is provided, lays out against
     * the list edge.
     *
     * @param view The view to measure for layout.
     * @param adjacent (Optional) The adjacent view, may be null to align to the
     *            list edge.
     * @param margins Layout margins to apply to the view.
     * @param out Rectangle into which measured bounds are placed.
     */
    private void measureViewToSide(View view, View adjacent, Rect margins, Rect out) {
        final int marginLeft;
        final int marginTop;
        final int marginRight;
        if (margins == null) {
            marginLeft = 0;
            marginTop = 0;
            marginRight = 0;
        } else {
            marginLeft = margins.left;
            marginTop = margins.top;
            marginRight = margins.right;
        }

        final int listWidth = mList.getWidth();
        final int maxWidth;
        if (adjacent == null) {
            maxWidth = listWidth;
        } else if (mLayoutFromRight) {
            maxWidth = adjacent.getLeft();
        } else {
            maxWidth = listWidth - adjacent.getRight();
        }

        final int adjMaxWidth = maxWidth - marginLeft - marginRight;
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(adjMaxWidth, MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        view.measure(widthMeasureSpec, heightMeasureSpec);

        // Align to the left or right.
        final int width = view.getMeasuredWidth();
        final int left;
        final int right;
        if (mLayoutFromRight) {
            right = (adjacent == null ? listWidth : adjacent.getLeft()) - marginRight;
            left = right - width;
        } else {
            left = (adjacent == null ? 0 : adjacent.getRight()) + marginLeft;
            right = left + width;
        }

        // Don't adjust the vertical position.
        final int top = marginTop;
        final int bottom = top + view.getMeasuredHeight();
        out.set(left, top, right, bottom);
    }

    private void measureFloating(View preview, Rect margins, Rect out) {
        final int marginLeft;
        final int marginTop;
        final int marginRight;
        if (margins == null) {
            marginLeft = 0;
            marginTop = 0;
            marginRight = 0;
        } else {
            marginLeft = margins.left;
            marginTop = margins.top;
            marginRight = margins.right;
        }

        final View list = mList;
        final int listWidth = list.getWidth();
        final int adjMaxWidth = listWidth - marginLeft - marginRight;
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(adjMaxWidth, MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        preview.measure(widthMeasureSpec, heightMeasureSpec);

        // Align at the vertical center, 10% from the top.
        final int width = preview.getMeasuredWidth();
        final int top = list.getHeight() / 10 + marginTop;
        final int bottom = top + preview.getMeasuredHeight();
        final int left = (listWidth - width) / 2;
        final int right = left + width;
        out.set(left, top, right, bottom);
    }

    /**
     * Lays out the thumb according to the current scrollbar position.
     */
    private void layoutThumb() {
        final Rect bounds = mTempBounds;
        measureViewToSide(mThumbImage, null, null, bounds);
        applyLayout(mThumbImage, bounds);
    }

    /**
     * Lays out the track centered on the thumb, if available, or against the
     * edge if no thumb is available. Must be called after {@link #layoutThumb}.
     */
    private void layoutTrack() {
        final View track = mTrackImage;
        final View thumb = mThumbImage;
        final View list = mList;
        final int listWidth = list.getWidth();
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(listWidth, MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        track.measure(widthMeasureSpec, heightMeasureSpec);

        final int trackWidth = track.getMeasuredWidth();
        final int thumbHalfHeight = thumb == null ? 0 : thumb.getHeight() / 2;
        final int left = thumb == null ? listWidth - trackWidth :
            thumb.getLeft() + (thumb.getWidth() - trackWidth) / 2;
        final int right = left + trackWidth;
        final int top = thumbHalfHeight;
        final int bottom = list.getHeight() - thumbHalfHeight;
        track.layout(left, top, right, bottom);
    }

    private void setState(int state) {
        mList.removeCallbacks(mDeferHide);

        if (mAlwaysShow && state == STATE_NONE) {
            state = STATE_VISIBLE;
        }

        if (state == mState) {
            return;
        }

        switch (state) {
            case STATE_NONE:
                transitionToHidden();
                break;
            case STATE_VISIBLE:
                transitionToVisible();
                break;
            case STATE_DRAGGING:
                transitionToDragging();
                break;
        }

        mState = state;

        refreshDrawablePressedState();
    }

    private void refreshDrawablePressedState() {
        final boolean isPressed = mState == STATE_DRAGGING;
        mThumbImage.setPressed(isPressed);
        mTrackImage.setPressed(isPressed);
    }

    /**
     * Shows nothing.
     */
    private void transitionToHidden() {
        if (mDecorAnimation != null) {
            mDecorAnimation.cancel();
        }

        final Animator fadeOut = groupAnimatorOfFloat(View.ALPHA, 0f, mThumbImage, mTrackImage,
                mPreviewImage, mPrimaryText, mSecondaryText).setDuration(DURATION_FADE_OUT);

        // Push the thumb and track outside the list bounds.
        final float offset = mLayoutFromRight ? mThumbImage.getWidth() : -mThumbImage.getWidth();
        final Animator slideOut = groupAnimatorOfFloat(
                View.TRANSLATION_X, offset, mThumbImage, mTrackImage)
                .setDuration(DURATION_FADE_OUT);

        mDecorAnimation = new AnimatorSet();
        mDecorAnimation.playTogether(fadeOut, slideOut);
        mDecorAnimation.start();
    }

    /**
     * Shows the thumb and track.
     */
    private void transitionToVisible() {
        if (mDecorAnimation != null) {
            mDecorAnimation.cancel();
        }

        final Animator fadeIn = groupAnimatorOfFloat(View.ALPHA, 1f, mThumbImage, mTrackImage)
                .setDuration(DURATION_FADE_IN);
        final Animator fadeOut = groupAnimatorOfFloat(
                View.ALPHA, 0f, mPreviewImage, mPrimaryText, mSecondaryText)
                .setDuration(DURATION_FADE_OUT);
        final Animator slideIn = groupAnimatorOfFloat(
                View.TRANSLATION_X, 0f, mThumbImage, mTrackImage).setDuration(DURATION_FADE_IN);

        mDecorAnimation = new AnimatorSet();
        mDecorAnimation.playTogether(fadeIn, fadeOut, slideIn);
        mDecorAnimation.start();
    }

    /**
     * Shows the thumb, preview, and track.
     */
    private void transitionToDragging() {
        if (mDecorAnimation != null) {
            mDecorAnimation.cancel();
        }

        final Animator fadeIn = groupAnimatorOfFloat(
                View.ALPHA, 1f, mThumbImage, mTrackImage, mPreviewImage)
                .setDuration(DURATION_FADE_IN);
        final Animator slideIn = groupAnimatorOfFloat(
                View.TRANSLATION_X, 0f, mThumbImage, mTrackImage).setDuration(DURATION_FADE_IN);

        mDecorAnimation = new AnimatorSet();
        mDecorAnimation.playTogether(fadeIn, slideIn);
        mDecorAnimation.start();

        // Ensure the preview text is correct.
        final String previewText = getPreviewText();
        transitionPreviewLayout(previewText);
    }

    private boolean isLongList(int visibleItemCount, int totalItemCount) {
        // Are there enough pages to require fast scroll? Recompute only if
        // total count changes.
        if (mItemCount != totalItemCount && visibleItemCount > 0) {
            mItemCount = totalItemCount;
            mLongList = mItemCount / visibleItemCount >= MIN_PAGES;
        }

        return mLongList;
    }

    public void onScroll(int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (!mAlwaysShow && !isLongList(visibleItemCount, totalItemCount)) {
            setState(STATE_NONE);
            return;
        }

        final boolean hasMoreItems = totalItemCount - visibleItemCount > 0;
        if (hasMoreItems && mState != STATE_DRAGGING) {
            setThumbPos(getPosFromItemCount(firstVisibleItem, visibleItemCount, totalItemCount));
        }

        mScrollCompleted = true;

        if (mFirstVisibleItem != firstVisibleItem) {
            mFirstVisibleItem = firstVisibleItem;

            // Show the thumb, if necessary, and set up auto-fade.
            if (mState != STATE_DRAGGING) {
                setState(STATE_VISIBLE);
                mList.postDelayed(mDeferHide, FADE_TIMEOUT);
            }
        }
    }

    private void getSectionsFromIndexer() {
        mSectionIndexer = null;

        Adapter adapter = mList.getAdapter();
        if (adapter instanceof HeaderViewListAdapter) {
            mHeaderCount = ((HeaderViewListAdapter) adapter).getHeadersCount();
            adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
        }

        if (adapter instanceof ExpandableListConnector) {
            final ExpandableListAdapter expAdapter = ((ExpandableListConnector) adapter)
                    .getAdapter();
            if (expAdapter instanceof SectionIndexer) {
                mSectionIndexer = (SectionIndexer) expAdapter;
                mListAdapter = (BaseAdapter) adapter;
                mSections = mSectionIndexer.getSections();
            }
        } else if (adapter instanceof SectionIndexer) {
            mListAdapter = (BaseAdapter) adapter;
            mSectionIndexer = (SectionIndexer) adapter;
            mSections = mSectionIndexer.getSections();
        } else {
            mListAdapter = (BaseAdapter) adapter;
            mSections = null;
        }
    }

    public void onSectionsChanged() {
        mListAdapter = null;
    }

    /**
     * Scrolls to a specific position within the section
     * @param position
     */
    private void scrollTo(float position) {
        mScrollCompleted = false;

        final int count = mList.getCount();
        final Object[] sections = mSections;
        final int sectionCount = sections == null ? 0 : sections.length;
        int sectionIndex;
        if (sections != null && sectionCount > 1) {
            final int exactSection = MathUtils.constrain(
                    (int) (position * sectionCount), 0, sectionCount - 1);
            int targetSection = exactSection;
            int targetIndex = mSectionIndexer.getPositionForSection(targetSection);
            sectionIndex = targetSection;

            // Given the expected section and index, the following code will
            // try to account for missing sections (no names starting with..)
            // It will compute the scroll space of surrounding empty sections
            // and interpolate the currently visible letter's range across the
            // available space, so that there is always some list movement while
            // the user moves the thumb.
            int nextIndex = count;
            int prevIndex = targetIndex;
            int prevSection = targetSection;
            int nextSection = targetSection + 1;

            // Assume the next section is unique
            if (targetSection < sectionCount - 1) {
                nextIndex = mSectionIndexer.getPositionForSection(targetSection + 1);
            }

            // Find the previous index if we're slicing the previous section
            if (nextIndex == targetIndex) {
                // Non-existent letter
                while (targetSection > 0) {
                    targetSection--;
                    prevIndex = mSectionIndexer.getPositionForSection(targetSection);
                    if (prevIndex != targetIndex) {
                        prevSection = targetSection;
                        sectionIndex = targetSection;
                        break;
                    } else if (targetSection == 0) {
                        // When section reaches 0 here, sectionIndex must follow it.
                        // Assuming mSectionIndexer.getPositionForSection(0) == 0.
                        sectionIndex = 0;
                        break;
                    }
                }
            }

            // Find the next index, in case the assumed next index is not
            // unique. For instance, if there is no P, then request for P's
            // position actually returns Q's. So we need to look ahead to make
            // sure that there is really a Q at Q's position. If not, move
            // further down...
            int nextNextSection = nextSection + 1;
            while (nextNextSection < sectionCount &&
                    mSectionIndexer.getPositionForSection(nextNextSection) == nextIndex) {
                nextNextSection++;
                nextSection++;
            }

            // Compute the beginning and ending scroll range percentage of the
            // currently visible section. This could be equal to or greater than
            // (1 / nSections). If the target position is near the previous
            // position, snap to the previous position.
            final float prevPosition = (float) prevSection / sectionCount;
            final float nextPosition = (float) nextSection / sectionCount;
            final float snapThreshold = (count == 0) ? Float.MAX_VALUE : .125f / count;
            if (prevSection == exactSection && position - prevPosition < snapThreshold) {
                targetIndex = prevIndex;
            } else {
                targetIndex = prevIndex + (int) ((nextIndex - prevIndex) * (position - prevPosition)
                    / (nextPosition - prevPosition));
            }

            // Clamp to valid positions.
            targetIndex = MathUtils.constrain(targetIndex, 0, count - 1);

            if (mList instanceof ExpandableListView) {
                final ExpandableListView expList = (ExpandableListView) mList;
                expList.setSelectionFromTop(expList.getFlatListPosition(
                        ExpandableListView.getPackedPositionForGroup(targetIndex + mHeaderCount)),
                        0);
            } else if (mList instanceof ListView) {
                ((ListView) mList).setSelectionFromTop(targetIndex + mHeaderCount, 0);
            } else {
                mList.setSelection(targetIndex + mHeaderCount);
            }
        } else {
            final int index = MathUtils.constrain((int) (position * count), 0, count - 1);

            if (mList instanceof ExpandableListView) {
                ExpandableListView expList = (ExpandableListView) mList;
                expList.setSelectionFromTop(expList.getFlatListPosition(
                        ExpandableListView.getPackedPositionForGroup(index + mHeaderCount)), 0);
            } else if (mList instanceof ListView) {
                ((ListView)mList).setSelectionFromTop(index + mHeaderCount, 0);
            } else {
                mList.setSelection(index + mHeaderCount);
            }

            sectionIndex = -1;
        }

        if (sectionIndex >= 0 && sectionIndex < sections.length) {
            // If we moved sections, display section.
            if (mCurrentSection != sectionIndex) {
                mCurrentSection = sectionIndex;
                final String section = sections[sectionIndex].toString();
                transitionToDragging();
                transitionPreviewLayout(section);
            }
        } else {
            // No current section, transition out of preview.
            transitionPreviewLayout(null);
            transitionToVisible();
        }
    }

    private String getPreviewText() {
        final Object[] sections = mSections;
        if (sections == null) {
            return null;
        }

        final int sectionIndex = mCurrentSection;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return null;
        }

        return sections[sectionIndex].toString();
    }

    /**
     * Transitions the preview text to a new value. Handles animation,
     * measurement, and layout.
     *
     * @param text The preview text to transition to.
     */
    private void transitionPreviewLayout(CharSequence text) {
        final Rect bounds = mTempBounds;
        final ImageView preview = mPreviewImage;
        final TextView showing;
        final TextView target;
        if (mShowingPrimary) {
            showing = mPrimaryText;
            target = mSecondaryText;
        } else {
            showing = mSecondaryText;
            target = mPrimaryText;
        }

        // Set and layout target immediately.
        target.setText(text);
        measurePreview(target, bounds);
        applyLayout(target, bounds);

        if (mPreviewAnimation != null) {
            mPreviewAnimation.cancel();
        }

        // Cross-fade preview text.
        final Animator showTarget = animateAlpha(target, 1f).setDuration(DURATION_CROSS_FADE);
        final Animator hideShowing = animateAlpha(showing, 0f).setDuration(DURATION_CROSS_FADE);
        hideShowing.addListener(mSwitchPrimaryListener);

        // Apply preview image padding and animate bounds, if necessary.
        bounds.left -= mPreviewImage.getPaddingLeft();
        bounds.top -= mPreviewImage.getPaddingTop();
        bounds.right += mPreviewImage.getPaddingRight();
        bounds.bottom += mPreviewImage.getPaddingBottom();
        final Animator resizePreview = animateBounds(preview, bounds);
        resizePreview.setDuration(DURATION_RESIZE);

        mPreviewAnimation = new AnimatorSet();
        final AnimatorSet.Builder builder = mPreviewAnimation.play(hideShowing).with(showTarget);
        builder.with(resizePreview);

        // The current preview size is unaffected by hidden or showing. It's
        // used to set starting scales for things that need to be scaled down.
        final int previewWidth = preview.getWidth() - preview.getPaddingLeft()
                - preview.getPaddingRight();

        // If target is too large, shrink it immediately to fit and expand to
        // target size. Otherwise, start at target size.
        final int targetWidth = target.getWidth();
        if (targetWidth > previewWidth) {
            target.setScaleX((float) previewWidth / targetWidth);
            final Animator scaleAnim = animateScaleX(target, 1f).setDuration(DURATION_RESIZE);
            builder.with(scaleAnim);
        } else {
            target.setScaleX(1f);
        }

        // If showing is larger than target, shrink to target size.
        final int showingWidth = showing.getWidth();
        if (showingWidth > targetWidth) {
            final float scale = (float) targetWidth / showingWidth;
            final Animator scaleAnim = animateScaleX(showing, scale).setDuration(DURATION_RESIZE);
            builder.with(scaleAnim);
        }

        mPreviewAnimation.start();
    }

    /**
     * Positions the thumb and preview widgets.
     *
     * @param position The position, between 0 and 1, along the track at which
     *            to place the thumb.
     */
    private void setThumbPos(float position) {
        final int top = 0;
        final int bottom = mList.getHeight();

        final float thumbHalfHeight = mThumbImage.getHeight() / 2f;
        final float min = top + thumbHalfHeight;
        final float max = bottom - thumbHalfHeight;
        final float offset = min;
        final float range = max - min;
        final float thumbMiddle = position * range + offset;
        mThumbImage.setTranslationY(thumbMiddle - thumbHalfHeight);

        // Center the preview on the thumb, constrained to the list bounds.
        final float previewHalfHeight = mPreviewImage.getHeight() / 2f;
        final float minP = top + previewHalfHeight;
        final float maxP = bottom - previewHalfHeight;
        final float previewMiddle = MathUtils.constrain(thumbMiddle, minP, maxP);
        final float previewTop = previewMiddle - previewHalfHeight;

        mPreviewImage.setTranslationY(previewTop);
        mPrimaryText.setTranslationY(previewTop);
        mSecondaryText.setTranslationY(previewTop);
    }

    private float getPosFromMotionEvent(float y) {
        final int top = 0;
        final int bottom = mList.getHeight();

        final float thumbHalfHeight = mThumbImage.getHeight() / 2f;
        final float min = top + thumbHalfHeight;
        final float max = bottom - thumbHalfHeight;
        final float offset = min;
        final float range = max - min;

        // If the list is the same height as the thumbnail or shorter,
        // effectively disable scrolling.
        if (range <= 0) {
            return 0f;
        }

        return MathUtils.constrain((y - offset) / range, 0f, 1f);
    }

    private float getPosFromItemCount(
            int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (mSectionIndexer == null || mListAdapter == null) {
            getSectionsFromIndexer();
        }

        final boolean hasSections = mSectionIndexer != null && mSections != null
                && mSections.length > 0;
        if (!hasSections || !mMatchDragPosition) {
            return firstVisibleItem / (totalItemCount - visibleItemCount);
        }

        firstVisibleItem -= mHeaderCount;
        if (firstVisibleItem < 0) {
            return 0;
        }

        totalItemCount -= mHeaderCount;

        final int section = mSectionIndexer.getSectionForPosition(firstVisibleItem);
        final int sectionPos = mSectionIndexer.getPositionForSection(section);
        final int nextSectionPos = mSectionIndexer.getPositionForSection(section + 1);
        final int sectionCount = mSections.length;
        final int positionsInSection = Math.max(1, nextSectionPos - sectionPos);

        final View child = mList.getChildAt(0);
        final float incrementalPos = child == null ? 0 : firstVisibleItem +
                (float) (mList.getPaddingTop() - child.getTop()) / Math.max(1, child.getHeight());
        final float posWithinSection = (incrementalPos - sectionPos) / positionsInSection;
        return (section + posWithinSection) / sectionCount;
    }

    /**
     * Cancels an ongoing fling event by injecting a
     * {@link MotionEvent#ACTION_CANCEL} into the host view.
     */
    private void cancelFling() {
        final MotionEvent cancelFling = MotionEvent.obtain(
                0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        mList.onTouchEvent(cancelFling);
        cancelFling.recycle();
    }

    /**
     * Cancels a pending drag.
     *
     * @see #startPendingDrag()
     */
    private void cancelPendingDrag() {
        mList.removeCallbacks(mDeferStartDrag);
        mHasPendingDrag = false;
    }

    /**
     * Delays dragging until after the framework has determined that the user is
     * scrolling, rather than tapping.
     */
    private void startPendingDrag() {
        mHasPendingDrag = true;
        mList.postDelayed(mDeferStartDrag, TAP_TIMEOUT);
    }

    private void beginDrag() {
        setState(STATE_DRAGGING);

        if (mListAdapter == null && mList != null) {
            getSectionsFromIndexer();
        }

        if (mList != null) {
            mList.requestDisallowInterceptTouchEvent(true);
            mList.reportScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
        }

        cancelFling();
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isPointInside(ev.getX(), ev.getY())) {
                    // If the parent has requested that its children delay
                    // pressed state (e.g. is a scrolling container) then we
                    // need to allow the parent time to decide whether it wants
                    // to intercept events. If it does, we will receive a CANCEL
                    // event.
                    if (mList.isInScrollingContainer()) {
                        mInitialTouchY = ev.getY();
                        startPendingDrag();
                        return false;
                    }

                    beginDrag();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPendingDrag();
                break;
        }

        return false;
    }

    public boolean onTouchEvent(MotionEvent me) {
        switch (me.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (isPointInside(me.getX(), me.getY())) {
                    beginDrag();
                    return true;
                }
            } break;

            case MotionEvent.ACTION_UP: {
                if (mHasPendingDrag) {
                    // Allow a tap to scroll.
                    beginDrag();

                    final float pos = getPosFromMotionEvent(me.getY());
                    setThumbPos(pos);
                    scrollTo(pos);

                    cancelPendingDrag();
                    // Will hit the STATE_DRAGGING check below
                }

                if (mState == STATE_DRAGGING) {
                    if (mList != null) {
                        // ViewGroup does the right thing already, but there might
                        // be other classes that don't properly reset on touch-up,
                        // so do this explicitly just in case.
                        mList.requestDisallowInterceptTouchEvent(false);
                        mList.reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                    }

                    setState(STATE_VISIBLE);
                    mList.postDelayed(mDeferHide, FADE_TIMEOUT);

                    return true;
                }
            } break;

            case MotionEvent.ACTION_MOVE: {
                if (mHasPendingDrag && Math.abs(me.getY() - mInitialTouchY) > mScaledTouchSlop) {
                    setState(STATE_DRAGGING);

                    if (mListAdapter == null && mList != null) {
                        getSectionsFromIndexer();
                    }

                    if (mList != null) {
                        mList.requestDisallowInterceptTouchEvent(true);
                        mList.reportScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                    }

                    cancelFling();
                    cancelPendingDrag();
                    // Will hit the STATE_DRAGGING check below
                }

                if (mState == STATE_DRAGGING) {
                    // TODO: Ignore jitter.
                    final float pos = getPosFromMotionEvent(me.getY());
                    setThumbPos(pos);

                    // If the previous scrollTo is still pending
                    if (mScrollCompleted) {
                        scrollTo(pos);
                    }

                    return true;
                }
            } break;

            case MotionEvent.ACTION_CANCEL: {
                cancelPendingDrag();
            } break;
        }

        return false;
    }

    /**
     * Returns whether a coordinate is inside the scroller's activation area. If
     * there is a track image, touching anywhere within the thumb-width of the
     * track activates scrolling. Otherwise, the user has to touch inside thumb
     * itself.
     *
     * @param x The x-coordinate.
     * @param y The y-coordinate.
     * @return Whether the coordinate is inside the scroller's activation area.
     */
    private boolean isPointInside(float x, float y) {
        return isPointInsideX(x) && (mHasTrackImage || isPointInsideY(y));
    }

    private boolean isPointInsideX(float x) {
        if (mLayoutFromRight) {
            return x >= mThumbImage.getLeft();
        } else {
            return x <= mThumbImage.getRight();
        }
    }

    private boolean isPointInsideY(float y) {
        return y >= mThumbImage.getTop() && y <= mThumbImage.getBottom();
    }

    /**
     * Constructs an animator for the specified property on a group of views.
     * See {@link ObjectAnimator#ofFloat(Object, String, float...)} for
     * implementation details.
     *
     * @param property The property being animated.
     * @param value The value to which that property should animate.
     * @param views The target views to animate.
     * @return An animator for all the specified views.
     */
    private static Animator groupAnimatorOfFloat(
            Property<View, Float> property, float value, View... views) {
        AnimatorSet animSet = new AnimatorSet();
        AnimatorSet.Builder builder = null;

        for (int i = views.length - 1; i >= 0; i--) {
            final Animator anim = ObjectAnimator.ofFloat(views[i], property, value);
            if (builder == null) {
                builder = animSet.play(anim);
            } else {
                builder.with(anim);
            }
        }

        return animSet;
    }

    /**
     * Returns an animator for the view's scaleX value.
     */
    private static Animator animateScaleX(View v, float target) {
        return ObjectAnimator.ofFloat(v, View.SCALE_X, target);
    }

    /**
     * Returns an animator for the view's alpha value.
     */
    private static Animator animateAlpha(View v, float alpha) {
        return ObjectAnimator.ofFloat(v, View.ALPHA, alpha);
    }

    /**
     * A Property wrapper around the <code>left</code> functionality handled by the
     * {@link View#setLeft(int)} and {@link View#getLeft()} methods.
     */
    private static Property<View, Integer> LEFT = new IntProperty<View>("left") {
        @Override
        public void setValue(View object, int value) {
            object.setLeft(value);
        }

        @Override
        public Integer get(View object) {
            return object.getLeft();
        }
    };

    /**
     * A Property wrapper around the <code>top</code> functionality handled by the
     * {@link View#setTop(int)} and {@link View#getTop()} methods.
     */
    private static Property<View, Integer> TOP = new IntProperty<View>("top") {
        @Override
        public void setValue(View object, int value) {
            object.setTop(value);
        }

        @Override
        public Integer get(View object) {
            return object.getTop();
        }
    };

    /**
     * A Property wrapper around the <code>right</code> functionality handled by the
     * {@link View#setRight(int)} and {@link View#getRight()} methods.
     */
    private static Property<View, Integer> RIGHT = new IntProperty<View>("right") {
        @Override
        public void setValue(View object, int value) {
            object.setRight(value);
        }

        @Override
        public Integer get(View object) {
            return object.getRight();
        }
    };

    /**
     * A Property wrapper around the <code>bottom</code> functionality handled by the
     * {@link View#setBottom(int)} and {@link View#getBottom()} methods.
     */
    private static Property<View, Integer> BOTTOM = new IntProperty<View>("bottom") {
        @Override
        public void setValue(View object, int value) {
            object.setBottom(value);
        }

        @Override
        public Integer get(View object) {
            return object.getBottom();
        }
    };

    /**
     * Returns an animator for the view's bounds.
     */
    private static Animator animateBounds(View v, Rect bounds) {
        final PropertyValuesHolder left = PropertyValuesHolder.ofInt(LEFT, bounds.left);
        final PropertyValuesHolder top = PropertyValuesHolder.ofInt(TOP, bounds.top);
        final PropertyValuesHolder right = PropertyValuesHolder.ofInt(RIGHT, bounds.right);
        final PropertyValuesHolder bottom = PropertyValuesHolder.ofInt(BOTTOM, bounds.bottom);
        return ObjectAnimator.ofPropertyValuesHolder(v, left, top, right, bottom);
    }
}
