/*
 * Copyright 2015 Emanuel Moecklin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.utils.note.rteditor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Editable;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View.BaseSavedState;
import android.widget.TextView.BufferType;

import com.utils.note.rteditor.api.JustifyEditText;
import com.utils.note.rteditor.api.RTMediaFactory;
import com.utils.note.rteditor.api.format.RTEditable;
import com.utils.note.rteditor.api.format.RTFormat;
import com.utils.note.rteditor.api.format.RTHtml;
import com.utils.note.rteditor.api.format.RTPlainText;
import com.utils.note.rteditor.api.format.RTText;
import com.utils.note.rteditor.api.media.RTAudio;
import com.utils.note.rteditor.api.media.RTImage;
import com.utils.note.rteditor.api.media.RTMedia;
import com.utils.note.rteditor.api.media.RTVideo;
import com.utils.note.rteditor.effects.Effect;
import com.utils.note.rteditor.effects.Effects;
import com.utils.note.rteditor.effects.ParagraphEffect;
import com.utils.note.rteditor.spans.AudioSpan;
import com.utils.note.rteditor.spans.LinkSpan;
import com.utils.note.rteditor.spans.LinkSpan.LinkSpanListener;
import com.utils.note.rteditor.spans.MediaSpan;
import com.utils.note.rteditor.utils.Paragraph;
import com.utils.note.rteditor.utils.RTLayout;
import com.utils.note.rteditor.utils.Selection;

/**
 * The actual rich text editor (extending android.widget.EditText).
 */
public class RTEditText extends JustifyEditText implements TextWatcher, SpanWatcher, LinkSpanListener,AudioSpan.AudioSpanListener {

    /**
     * The interface to be implemented by the RTManager to listen to RTEditText
     * events.
     */
    public interface RTEditTextListener {

        /*
         * If this EditText changes focus the listener will be informed through
         * this method.
         */
        void onFocusChanged(RTEditText editor, boolean focused);

        /*
         * Provides details of the new selection, including the start and ending
         * character positions, and the id of this RTEditText component.
         */
        void onSelectionChanged(RTEditText editor, int start, int end);

        /*
         * Text and or text effects have changed (used for undo/redo function).
         */
        void onTextChanged(RTEditText editor, Spannable before, Spannable after,
                           int selStartBefore, int selEndBefore, int selStartAfter, int selEndAfter);

        /*
         * A link in a LinkSpan has been clicked.
         */
        public void onClick(RTEditText editor, LinkSpan span);
        public void onClick(RTEditText editor, AudioSpan span);
        /*
         * Rich text editing was enabled/disabled for this editor.
         */
        public void onRichTextEditingChanged(RTEditText editor, boolean useRichText);

    }

    // don't allow any formatting in text mode
    private boolean mUseRTFormatting = true;

    // for performance reasons we compute a new layout only if the text has changed
    private boolean mLayoutChanged;
    private RTLayout mRTLayout;    // don't call this mLayout because TextView has a mLayout too (no shadowing as both are private but still...)

    // while onSaveInstanceState() is running, don't modify any spans
    private boolean mIsSaving;

    /// while selection is changing don't apply any effects
    private boolean mIsSelectionChanging = false;

    // text has changed
    private boolean mTextChanged;

    // this indicates whether text is selected or not -> ignore window focus changes (by spinners)
    private boolean mTextSelected;

    private RTEditTextListener mListener;

    private RTMediaFactory<RTImage, RTAudio, RTVideo> mMediaFactory;

    // used to check if selection has changed
    private int mOldSelStart = -1;
    private int mOldSelEnd = -1;

	/*
     * Used for the undo / redo functions
	 */

    // if True then text changes are not registered for undo/redo
    // we need this during the actual undo/redo operation (or an undo would create a change event itself)
    private boolean mIgnoreTextChange;

    private int mSelStartBefore;        // selection start before text changed
    private int mSelEndBefore;          // selection end before text changed
    private String mOldText;            // old text before it changed
    private String mNewText;            // new text after it changed (needed in afterTextChanged to see if the text has changed)
    private Spannable mOldSpannable;    // undo/redo

    // we need to keep track of the media for this editor to be able to clean up after we're done
    private Set<RTMedia> mOriginalMedia = new HashSet<RTMedia>();
    private Set<RTMedia> mAddedMedia = new HashSet<RTMedia>();

    // ****************************************** Lifecycle Methods *******************************************

    public RTEditText(Context context) {
        super(context);
        init();
    }

    public RTEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RTEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        addTextChangedListener(this);
        // we need this or links won't be clickable
        setMovementMethod(RTEditorMovementMethod.getInstance());
    }

    /**
     * @param isSaved True if the text is saved, False if it's dismissed
     */
    void onDestroy(boolean isSaved) {
        // make sure all obsolete MediaSpan files are removed from the file system:
        // - when saving the text delete the MediaSpan if it was deleted
        // - when dismissing the text delete the MediaSpan if it was deleted and not saved before

        // collect all media the editor contains currently
        Set<RTMedia> mCurrentMedia = new HashSet<RTMedia>();
        Spannable text = getText();
        for (MediaSpan span : text.getSpans(0, text.length(), MediaSpan.class)) {
            mCurrentMedia.add(span.getMedia());
        }

        // now delete all those that aren't needed any longer
        Set<RTMedia> mMedia2Delete = isSaved ? mOriginalMedia : mCurrentMedia;
        mMedia2Delete.addAll(mAddedMedia);
        Set<RTMedia> mMedia2Keep = isSaved ? mCurrentMedia : mOriginalMedia;
        for (RTMedia media : mMedia2Delete) {
            if (!mMedia2Keep.contains(media)) {
                media.remove();
            }
        }
    }

    /*
     * Needs to be called if a media is added to the editor.
     * Important to be able to delete obsolete media once we're done editing.
     */
    void onAddMedia(RTMedia media) {
        mAddedMedia.add(media);
    }

    /**
     * This needs to be called before anything else because we need the media
     * factory.
     *
     * @param listener     The RTEditTextListener (the RTManager)
     * @param mediaFactory The RTMediaFactory
     */
    void register(RTEditTextListener listener, RTMediaFactory<RTImage, RTAudio, RTVideo> mediaFactory) {
        mListener = listener;
        mMediaFactory = mediaFactory;
    }

    void unregister() {
        mListener = null;
        mMediaFactory = null;
    }

    /**
     * Return all paragraphs as as array of selection objects
     */
    public List<Paragraph> getParagraphs() {
        RTLayout layout = getRTLayout();
        return layout.getParagraphs();
    }

    /**
     * Find the start and end of the paragraph(s) encompassing the current selection.
     * A paragraph spans from one \n (exclusive) to the next one (inclusive)
     */
    public Selection getParagraphsInSelection() {
        RTLayout layout = getRTLayout();

        Selection selection = new Selection(this);
        int firstLine = layout.getLineForOffset(selection.start());
        int end = selection.isEmpty() ? selection.end() : selection.end() - 1;
        int lastLine = layout.getLineForOffset(end);

        return new Selection(layout.getLineStart(firstLine), layout.getLineEnd(lastLine));
    }

    private RTLayout getRTLayout() {
        synchronized (this) {
            if (mRTLayout == null || mLayoutChanged) {
                mRTLayout = new RTLayout(getText());
                mLayoutChanged = false;
            }
        }
        return mRTLayout;
    }

    /**
     * This method returns the Selection which makes sure that selection start is <= selection end.
     * Note: getSelectionStart()/getSelectionEnd() refer to the order in which text was selected.
     */
    Selection getSelection() {
        int selStart = getSelectionStart();
        int selEnd = getSelectionEnd();
        return new Selection(selStart, selEnd);
    }

    /**
     * @return the selected text (needed when creating links)
     */
    String getSelectedText() {
        Spannable text = getText();
        Selection sel = getSelection();
        if (sel.start() >= 0 && sel.end() >= 0 && sel.end() <= text.length()) {
            return text.subSequence(sel.start(), sel.end()).toString();
        }
        return null;
    }

    public Spannable cloneSpannable() {
        CharSequence text = super.getText();
        return new ClonedSpannableString(text != null ? text : "");
    }

    // ****************************************** Set/Get Text Methods *******************************************

    /**
     * Sets the edit mode to plain or rich text. The text will be converted
     * automatically to rich/plain text if autoConvert is True.
     *
     * @param useRTFormatting True if the edit mode should be rich text, False if the edit
     *                        mode should be plain text
     * @param autoConvert     Automatically convert the content to plain or rich text if
     *                        this is True
     */
    public void setRichTextEditing(boolean useRTFormatting, boolean autoConvert) {
        assertRegistration();

        if (useRTFormatting != mUseRTFormatting) {
            mUseRTFormatting = useRTFormatting;

            if (autoConvert) {
                RTFormat targetFormat = useRTFormatting ? RTFormat.PLAIN_TEXT : RTFormat.HTML;
                setText(getRichText(targetFormat));
            }

            if (mListener != null) {
                mListener.onRichTextEditingChanged(this, mUseRTFormatting);
            }
        }
    }

    /**
     * Sets the edit mode to plain or rich text and updates the content at the
     * same time. The caller needs to make sure the content matches the correct
     * format (if you pass in html code as plain text the editor will show the
     * html code).
     *
     *设置编辑模式，以纯文本或富文本，并同时更新内容。调用者需要确保内容按正确格式匹配（如果你传入HTML代码用纯文本的方式显示，编辑控件将显示HTML代码）。
     *
     * @param useRTFormatting true：编辑模式为富文本，false：编辑模式为纯文本
     * @param content  显示内容
     */
    public void setRichTextEditing(boolean useRTFormatting, String content) {
        assertRegistration();

        if (useRTFormatting != mUseRTFormatting) {
            mUseRTFormatting = useRTFormatting;

            if (mListener != null) {
                mListener.onRichTextEditingChanged(this, mUseRTFormatting);
            }
        }

        RTText rtText = useRTFormatting ?
                new RTHtml<RTImage, RTAudio, RTVideo>(RTFormat.HTML, content) :
                new RTPlainText(content);

        setText(rtText);
    }

    /**
     * Set the text for this editor.
     * <p>
     * It will convert the text from rich text to plain text if the editor's
     * mode is set to use plain text. or to a spanned text (only supported
     * formatting) if the editor's mode is set to use rich text
     * <p>
     * We need to prevent onSelectionChanged() to do anything as long as
     * setText() hasn't finished because the Layout doesn't seem to update
     * before setText has finished but onSelectionChanged will still be called
     * during setText and will receive the out-dated Layout which doesn't allow
     * us to apply styles and such.
     */
    public void setText(RTText rtText) {
        assertRegistration();

        if (rtText.getFormat() instanceof RTFormat.Html) {
            if (mUseRTFormatting) {
                RTText rtSpanned = rtText.convertTo(RTFormat.SPANNED, mMediaFactory);

                super.setText(rtSpanned.getText(), BufferType.EDITABLE);
                addSpanWatcher();

                // collect all current media
                Spannable text = getText();
                for (MediaSpan span : text.getSpans(0, text.length(), MediaSpan.class)) {
                    mOriginalMedia.add(span.getMedia());
                }

                Effects.cleanupParagraphs(this);
            } else {
                RTText rtPlainText = rtText.convertTo(RTFormat.PLAIN_TEXT, mMediaFactory);
                super.setText(rtPlainText.getText());
            }
        } else if (rtText.getFormat() instanceof RTFormat.PlainText) {
            CharSequence text = rtText.getText();
            super.setText(text == null ? "" : text.toString());
        }

        onSelectionChanged(0, 0);
    }

    public boolean usesRTFormatting() {
        return mUseRTFormatting;
    }

    /**
     * Returns the content of this editor as a String. The caller is responsible
     * to call only formats that are supported by RTEditable (which is the rich
     * text editor's format and always the source format).
     *
     * @param format The RTFormat the text should be converted to.
     * @throws UnsupportedOperationException if the target format isn't supported.
     */
    public String getText(RTFormat format) {
        return getRichText(format).getText().toString();
    }

    /**
     * Same as "String getText(RTFormat format)" but this method returns the
     * RTText instead of just the actual text.
     */
    public RTText getRichText(RTFormat format) {
        assertRegistration();

        RTEditable rtEditable = new RTEditable(this);
        return rtEditable.convertTo(format, mMediaFactory);
    }

    private void assertRegistration() {
        if (mMediaFactory == null) {
            throw new IllegalStateException("The RTMediaFactory is null. Please make sure to register the editor at the RTManager before using it.");
        }
    }

    // ****************************************** TextWatcher / SpanWatcher *******************************************

    public boolean hasChanged() {
        return mTextChanged;
    }

    public void resetHasChanged() {
        mTextChanged = false;
    }

    /**
     * Ignore changes that would trigger a RTEditTextListener.onTextChanged()
     * method call. We need this during the actual undo/redo operation (or an
     * undo would create a change event itself).
     */
    synchronized void ignoreTextChanges() {
        mIgnoreTextChange = true;
    }

    /**
     * If changes happen call RTEditTextListener.onTextChanged().
     * This is needed for the undo/redo functionality.
     */
    synchronized void registerTextChanges() {
        mIgnoreTextChange = false;
    }

    @Override
	/* TextWatcher */
    public synchronized void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // we use a String to get a static copy of the CharSequence (the CharSequence changes when the text changes...)
        String oldText = mOldText == null ? "" : mOldText;
        if (!mIgnoreTextChange && !s.toString().equals(oldText)) {
            mSelStartBefore = getSelectionStart();
            mSelEndBefore = getSelectionEnd();
            mOldText = s.toString();
            mNewText = mOldText;
            mOldSpannable = cloneSpannable();
        }
        mLayoutChanged = true;
    }

    @Override
	/* TextWatcher */
    public synchronized void onTextChanged(CharSequence s, int start, int before, int count) {
        mLayoutChanged = true;
        if(mRTOnTextChangeListener != null){
        	mRTOnTextChangeListener.onTextChanged(s, start, before, count);
        }
    }
    
    private TextWatcher mRTOnTextChangeListener;
    public void addMyTextChangeListener(TextWatcher listener){
    	mRTOnTextChangeListener = listener;
    }

    @Override
	/* TextWatcher */
    public synchronized void afterTextChanged(Editable s) {
        String theText = s.toString();
        String newText = mNewText == null ? "" : mNewText;
        if (mListener != null && !mIgnoreTextChange && !newText.equals(theText)) {
            Spannable newSpannable = cloneSpannable();
            mListener.onTextChanged(this, mOldSpannable, newSpannable, mSelStartBefore, mSelEndBefore, getSelectionStart(), getSelectionEnd());
            mNewText = theText;
        }
        mLayoutChanged = true;
        mTextChanged = true;
        addSpanWatcher();
    }

    @Override
	/* SpanWatcher */
    public void onSpanAdded(Spannable text, Object what, int start, int end) {
        mTextChanged = true;
    }

    @Override
	/* SpanWatcher */
    public void onSpanChanged(Spannable text, Object what, int ostart, int oend, int nstart, int nend) {
        mTextChanged = true;
    }

    @Override
	/* SpanWatcher */
    public void onSpanRemoved(Spannable text, Object what, int start, int end) {
        mTextChanged = true;
    }

    /**
     * Add a SpanWatcher for the Changeable implementation
     */
    private void addSpanWatcher() {
        Spannable spannable = getText();
        if (spannable.getSpans(0, spannable.length(), getClass()) != null) {
            spannable.setSpan(this, 0, spannable.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }

    // ****************************************** Listener Methods *******************************************

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        // if text is selected we ignore a loss of focus to prevent Android from terminating
        // text selection when one of the spinners opens (text size, color, bg color)
        if (!mUseRTFormatting || hasWindowFocus || !mTextSelected) {
            super.onWindowFocusChanged(hasWindowFocus);
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);

        if (mUseRTFormatting && mListener != null) {
            mListener.onFocusChanged(this, focused);
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        mIsSaving = true;

        Parcelable superState = super.onSaveInstanceState();
        String content = getText(mUseRTFormatting ? RTFormat.HTML : RTFormat.PLAIN_TEXT);
        SavedState savedState = new SavedState(superState, mUseRTFormatting, content);

        mIsSaving = false;
        return savedState;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if(state instanceof SavedState) {
            SavedState savedState = (SavedState)state;
            super.onRestoreInstanceState(savedState.getSuperState());
            setRichTextEditing(savedState.useRTFormatting(), savedState.getContent());
        }
        else {
            super.onRestoreInstanceState(state);
        }
    }

    private static class SavedState extends BaseSavedState {
        private String mContent;
        private boolean mUseRTFormatting;

        SavedState(Parcelable superState, boolean useRTFormatting, String content) {
            super(superState);

            mUseRTFormatting = useRTFormatting;
            mContent = content;
        }

        private String getContent() {
            return mContent;
        }

        private boolean useRTFormatting() {
            return mUseRTFormatting;
        }

        private SavedState(Parcel in) {
            super(in);

            mUseRTFormatting = in.readInt() == 1;
            mContent = in.readString();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);

            out.writeInt(mUseRTFormatting ? 1 : 0);
            out.writeString(mContent);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }

    @Override
    protected void onSelectionChanged(int start, int end) {
        if (mOldSelStart != start || mOldSelEnd != end) {
            mOldSelStart = start;
            mOldSelStart = end;

            mTextSelected = (end > start);

            super.onSelectionChanged(start, end);

            if (mUseRTFormatting) {

                if (!mIsSaving) {
                    Effects.cleanupParagraphs(this);
                }

                if (mListener != null) {
                    mIsSelectionChanging = true;
                    mListener.onSelectionChanged(this, start, end);
                    mIsSelectionChanging = false;
                }

            }
        }
    }

    /*
     * Call this to have an effect applied to the current selection.
     * You get the Effect object via the static data members (e.g., RTEditText.BOLD).
     * The value for most effects is a Boolean, indicating whether to add or remove the effect.
     */
    public <T> void applyEffect(Effect<T> effect, T value) {
        if (mUseRTFormatting && !mIsSelectionChanging && !mIsSaving) {
            Spannable oldSpannable = mIgnoreTextChange ? null : cloneSpannable();

            effect.applyToSelection(this, value);
            if (effect instanceof ParagraphEffect) {
                Effects.cleanupParagraphs(this);
            }

            synchronized (this) {
                if (mListener != null && !mIgnoreTextChange) {
                    Spannable newSpannable = cloneSpannable();
                    mListener.onTextChanged(this, oldSpannable, newSpannable, getSelectionStart(), getSelectionEnd(),
                            getSelectionStart(), getSelectionEnd());
                }
                mLayoutChanged = true;
            }
        }
    }

    @Override
	/* LinkSpanListener */
    public void onClick(LinkSpan linkSpan) {
        if (mUseRTFormatting && mListener != null) {
            mListener.onClick(this, linkSpan);
        }
    }

    @Override
    public void onClick(AudioSpan audioSpan) {
        if (mUseRTFormatting && mListener != null) {
            mListener.onClick(this,audioSpan);
        }
    }
}