/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;

import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.GroupCallActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

public class ChatObject {

    public static final int CHAT_TYPE_CHAT = 0;
    public static final int CHAT_TYPE_CHANNEL = 2;
    public static final int CHAT_TYPE_USER = 3;
    public static final int CHAT_TYPE_MEGAGROUP = 4;

    public static final int ACTION_PIN = 0;
    public static final int ACTION_CHANGE_INFO = 1;
    public static final int ACTION_BLOCK_USERS = 2;
    public static final int ACTION_INVITE = 3;
    public static final int ACTION_ADD_ADMINS = 4;
    public static final int ACTION_POST = 5;
    public static final int ACTION_SEND = 6;
    public static final int ACTION_SEND_MEDIA = 7;
    public static final int ACTION_SEND_STICKERS = 8;
    public static final int ACTION_EMBED_LINKS = 9;
    public static final int ACTION_SEND_POLLS = 10;
    public static final int ACTION_VIEW = 11;
    public static final int ACTION_EDIT_MESSAGES = 12;
    public static final int ACTION_DELETE_MESSAGES = 13;
    public static final int ACTION_MANAGE_CALLS = 14;

    public final static int VIDEO_FRAME_NO_FRAME = 0;
    public final static int VIDEO_FRAME_REQUESTING = 1;
    public final static int VIDEO_FRAME_HAS_FRAME = 2;

    private static final int MAX_PARTICIPANTS_COUNT = 5000;

    public static class Call {
        public TLRPC.GroupCall call;
        public int chatId;
        public SparseArray<TLRPC.TL_groupCallParticipant> participants = new SparseArray<>();
        public final ArrayList<TLRPC.TL_groupCallParticipant> sortedParticipants = new ArrayList<>();
        public final ArrayList<ChatObject.VideoParticipant> visibleVideoParticipants = new ArrayList<>();
        public final ArrayList<TLRPC.TL_groupCallParticipant> visibleParticipants = new ArrayList<>();
        public final HashMap<String, Bitmap> thumbs = new HashMap<>();

        private final HashMap<String, VideoParticipant> videoParticipantsCache = new HashMap<>();
        public ArrayList<Integer> invitedUsers = new ArrayList<>();
        public HashSet<Integer> invitedUsersMap = new HashSet<>();
        public SparseArray<TLRPC.TL_groupCallParticipant> participantsBySources = new SparseArray<>();
        public SparseArray<TLRPC.TL_groupCallParticipant> participantsByVideoSources = new SparseArray<>();
        public SparseArray<TLRPC.TL_groupCallParticipant> participantsByPresentationSources = new SparseArray<>();
        private String nextLoadOffset;
        public boolean membersLoadEndReached;
        public boolean loadingMembers;
        public boolean reloadingMembers;
        public boolean recording;
        public boolean canStreamVideo;
        public VideoParticipant videoNotAvailableParticipant;
        public AccountInstance currentAccount;
        public int speakingMembersCount;
        private Runnable typingUpdateRunnable = () -> {
            typingUpdateRunnableScheduled = false;
            checkOnlineParticipants();
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallTypingsUpdated);
        };
        private boolean typingUpdateRunnableScheduled;
        private int lastLoadGuid;
        private HashSet<Integer> loadingGuids = new HashSet<>();
        private ArrayList<TLRPC.TL_updateGroupCallParticipants> updatesQueue = new ArrayList<>();
        private long updatesStartWaitTime;

        public TLRPC.Peer selfPeer;

        private HashSet<Integer> loadingUids = new HashSet<>();
        private HashSet<Integer> loadingSsrcs = new HashSet<>();

        private Runnable checkQueueRunnable;

        private long lastGroupCallReloadTime;
        private boolean loadingGroupCall;
        private static int videoPointer;

        public final SparseArray<TLRPC.TL_groupCallParticipant> currentSpeakingPeers = new SparseArray<>();

        private final Runnable updateCurrentSpeakingRunnable = new Runnable() {
            @Override
            public void run() {
                long uptime = SystemClock.uptimeMillis();
                boolean update = false;
                for(int i = 0; i < currentSpeakingPeers.size(); i++) {
                    int key = currentSpeakingPeers.keyAt(i);
                    TLRPC.TL_groupCallParticipant participant = currentSpeakingPeers.get(key);
                    if (uptime - participant.lastSpeakTime >= 500) {
                        update = true;
                        currentSpeakingPeers.remove(key);
                        i--;
                    }
                }

                if (currentSpeakingPeers.size() > 0) {
                    AndroidUtilities.runOnUIThread(updateCurrentSpeakingRunnable, 550);
                }
                if (update) {
                    currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallSpeakingUsersUpdated, chatId, call.id, false);
                }
            }
        };

        public void setCall(AccountInstance account, int chatId, TLRPC.TL_phone_groupCall groupCall) {
            this.chatId = chatId;
            currentAccount = account;
            call = groupCall.call;
            recording = call.record_start_date != 0;
            int date = Integer.MAX_VALUE;
            for (int a = 0, N = groupCall.participants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant participant = groupCall.participants.get(a);
                participants.put(MessageObject.getPeerId(participant.peer), participant);
                sortedParticipants.add(participant);
                processAllSources(participant, true);
                date = Math.min(date, participant.date);
            }
            sortParticipants();
            nextLoadOffset = groupCall.participants_next_offset;
            loadMembers(true);

            createNoVideoParticipant();
        }

        public void createNoVideoParticipant() {
            if (videoNotAvailableParticipant != null) {
                return;
            }
            TLRPC.TL_groupCallParticipant noVideoParticipant = new TLRPC.TL_groupCallParticipant();
            noVideoParticipant.peer = new TLRPC.TL_peerChannel();
            noVideoParticipant.peer.channel_id = chatId;
            noVideoParticipant.muted = true;
            noVideoParticipant.video = new TLRPC.TL_groupCallParticipantVideo();
            noVideoParticipant.video.paused = true;
            noVideoParticipant.video.endpoint = "";

            videoNotAvailableParticipant = new VideoParticipant(noVideoParticipant, false, false);
        }

        public void addSelfDummyParticipant(boolean notify) {
            int selfId = getSelfId();
            if (participants.indexOfKey(selfId) >= 0) {
                return;
            }
            TLRPC.TL_groupCallParticipant selfDummyParticipant = new TLRPC.TL_groupCallParticipant();
            selfDummyParticipant.peer = selfPeer;
            selfDummyParticipant.muted = true;
            selfDummyParticipant.self = true;
            selfDummyParticipant.video_joined = call.can_start_video;
            TLRPC.Chat chat = currentAccount.getMessagesController().getChat(chatId);
            selfDummyParticipant.can_self_unmute = !call.join_muted || ChatObject.canManageCalls(chat);
            selfDummyParticipant.date = currentAccount.getConnectionsManager().getCurrentTime();
            if (ChatObject.canManageCalls(chat) || !ChatObject.isChannel(chat) || chat.megagroup || selfDummyParticipant.can_self_unmute) {
                selfDummyParticipant.active_date = currentAccount.getConnectionsManager().getCurrentTime();
            }
            if (selfId > 0) {
                TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount.getCurrentAccount()).getUserFull(selfId);
                if (userFull != null) {
                    selfDummyParticipant.about = userFull.about;
                }
            } else {
                TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount.getCurrentAccount()).getChatFull(-selfId);
                if (chatFull != null) {
                    selfDummyParticipant.about = chatFull.about;
                }
            }
            participants.put(selfId, selfDummyParticipant);
            sortedParticipants.add(selfDummyParticipant);
            sortParticipants();
            if (notify) {
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
            }
        }

        public void migrateToChat(TLRPC.Chat chat) {
            chatId = chat.id;
            VoIPService voIPService = VoIPService.getSharedInstance();
            if (voIPService != null && voIPService.getAccount() == currentAccount.getCurrentAccount() && voIPService.getChat() != null && voIPService.getChat().id == -chatId) {
                voIPService.migrateToChat(chat);
            }
        }

        public boolean shouldShowPanel() {
            return call.participants_count > 0 || isScheduled();
        }

        public boolean isScheduled() {
            return (call.flags & 128) != 0;
        }

        private int getSelfId() {
            int selfId;
            if (selfPeer != null) {
                return MessageObject.getPeerId(selfPeer);
            } else {
                return currentAccount.getUserConfig().getClientUserId();
            }
        }

        public void loadMembers(boolean fromBegin) {
            if (fromBegin) {
                if (reloadingMembers) {
                    return;
                }
                membersLoadEndReached = false;
                nextLoadOffset = null;
            }
            if (membersLoadEndReached || sortedParticipants.size() > MAX_PARTICIPANTS_COUNT) {
                return;
            }
            if (fromBegin) {
                reloadingMembers = true;
            }
            loadingMembers = true;
            TLRPC.TL_phone_getGroupParticipants req = new TLRPC.TL_phone_getGroupParticipants();
            req.call = getInputGroupCall();
            req.offset = nextLoadOffset != null ? nextLoadOffset : "";
            req.limit = 20;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                loadingMembers = false;
                if (fromBegin) {
                    reloadingMembers = false;
                }
                if (response != null) {
                    TLRPC.TL_phone_groupParticipants groupParticipants = (TLRPC.TL_phone_groupParticipants) response;
                    currentAccount.getMessagesController().putUsers(groupParticipants.users, false);
                    currentAccount.getMessagesController().putChats(groupParticipants.chats, false);
                    SparseArray<TLRPC.TL_groupCallParticipant> old = null;
                    int selfId = getSelfId();
                    TLRPC.TL_groupCallParticipant oldSelf = participants.get(selfId);
                    if (TextUtils.isEmpty(req.offset)) {
                        if (participants.size() != 0) {
                            old = participants;
                            participants = new SparseArray<>();
                        } else {
                            participants.clear();
                        }
                        sortedParticipants.clear();
                        participantsBySources.clear();
                        participantsByVideoSources.clear();
                        participantsByPresentationSources.clear();
                        loadingGuids.clear();
                    }
                    nextLoadOffset = groupParticipants.next_offset;
                    if (groupParticipants.participants.isEmpty() || TextUtils.isEmpty(nextLoadOffset)) {
                        membersLoadEndReached = true;
                    }
                    if (TextUtils.isEmpty(req.offset)) {
                        call.version = groupParticipants.version;
                        call.participants_count = groupParticipants.count;
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("new participants count " + call.participants_count);
                        }
                    }
                    long time = SystemClock.elapsedRealtime();
                    currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
                    boolean hasSelf = false;
                    for (int a = 0, N = groupParticipants.participants.size(); a <= N; a++) {
                        TLRPC.TL_groupCallParticipant participant;
                        if (a == N) {
                            if (fromBegin && oldSelf != null && !hasSelf) {
                                participant = oldSelf;
                            } else {
                                continue;
                            }
                        } else {
                            participant = groupParticipants.participants.get(a);
                            if (participant.self) {
                                hasSelf = true;
                            }
                        }
                        TLRPC.TL_groupCallParticipant oldParticipant = participants.get(MessageObject.getPeerId(participant.peer));
                        if (oldParticipant != null) {
                            sortedParticipants.remove(oldParticipant);
                            processAllSources(oldParticipant, false);
                            if (oldParticipant.self) {
                                participant.lastTypingDate = oldParticipant.active_date;
                            } else {
                                participant.lastTypingDate = Math.max(participant.active_date, oldParticipant.active_date);
                            }
                            if (time != participant.lastVisibleDate) {
                                participant.active_date = participant.lastTypingDate;
                            }
                        } else if (old != null) {
                            oldParticipant = old.get(MessageObject.getPeerId(participant.peer));
                            if (oldParticipant != null) {
                                if (oldParticipant.self) {
                                    participant.lastTypingDate = oldParticipant.active_date;
                                } else {
                                    participant.lastTypingDate = Math.max(participant.active_date, oldParticipant.active_date);
                                }
                                if (time != participant.lastVisibleDate) {
                                    participant.active_date = participant.lastTypingDate;
                                } else {
                                    participant.active_date = oldParticipant.active_date;
                                }
                            }
                        }
                        participants.put(MessageObject.getPeerId(participant.peer), participant);
                        sortedParticipants.add(participant);
                        processAllSources(participant, true);
                    }
                    if (call.participants_count < participants.size()) {
                        call.participants_count = participants.size();
                    }
                    sortParticipants();
                    currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
                    setParticiapantsVolume();
                }
            }));
        }

        private void setParticiapantsVolume() {
            VoIPService voIPService = VoIPService.getSharedInstance();
            if (voIPService != null && voIPService.getAccount() == currentAccount.getCurrentAccount() && voIPService.getChat() != null && voIPService.getChat().id == -chatId) {
                voIPService.setParticipantsVolume();
            }
        }

        public void setTitle(String title) {
            TLRPC.TL_phone_editGroupCallTitle req = new TLRPC.TL_phone_editGroupCallTitle();
            req.call = getInputGroupCall();
            req.title = title;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> {
                if (response != null) {
                    final TLRPC.Updates res = (TLRPC.Updates) response;
                    currentAccount.getMessagesController().processUpdates(res, false);
                }
            });
        }

        public void addInvitedUser(int uid) {
            if (participants.get(uid) != null || invitedUsersMap.contains(uid)) {
                return;
            }
            invitedUsersMap.add(uid);
            invitedUsers.add(uid);
        }

        public void processTypingsUpdate(AccountInstance accountInstance, ArrayList<Integer> uids, int date) {
            boolean updated = false;
            ArrayList<Integer> participantsToLoad = null;
            long time = SystemClock.elapsedRealtime();
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
            for (int a = 0, N = uids.size(); a < N; a++) {
                Integer id = uids.get(a);
                TLRPC.TL_groupCallParticipant participant = participants.get(id);
                if (participant != null) {
                    if (date - participant.lastTypingDate > 10) {
                        if (participant.lastVisibleDate != date) {
                            participant.active_date = date;
                        }
                        participant.lastTypingDate = date;
                        updated = true;
                    }
                } else {
                    if (participantsToLoad == null) {
                        participantsToLoad = new ArrayList<>();
                    }
                    participantsToLoad.add(id);
                }
            }
            if (participantsToLoad != null) {
                loadUnknownParticipants(participantsToLoad, true, null);
            }
            if (updated) {
                sortParticipants();
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
            }
        }

        private void loadUnknownParticipants(ArrayList<Integer> participantsToLoad, boolean isIds, OnParticipantsLoad onLoad) {
            HashSet<Integer> set = isIds ? loadingUids : loadingSsrcs;
            for (int a = 0, N = participantsToLoad.size(); a < N; a++) {
                if (set.contains(participantsToLoad.get(a))) {
                    participantsToLoad.remove(a);
                    a--;
                    N--;
                }
            }
            if (participantsToLoad.isEmpty()) {
                return;
            }
            int guid = ++lastLoadGuid;
            loadingGuids.add(guid);
            set.addAll(participantsToLoad);
            TLRPC.TL_phone_getGroupParticipants req = new TLRPC.TL_phone_getGroupParticipants();
            req.call = getInputGroupCall();
            if (isIds) {
                for (int a = 0, N = participantsToLoad.size(); a < N; a++) {
                    Integer uid = participantsToLoad.get(a);
                    if (uid > 0) {
                        TLRPC.TL_inputPeerUser peerUser = new TLRPC.TL_inputPeerUser();
                        peerUser.user_id = uid;
                        req.ids.add(peerUser);
                    } else {
                        TLRPC.Chat chat = currentAccount.getMessagesController().getChat(-uid);
                        TLRPC.InputPeer inputPeer;
                        if (chat == null || ChatObject.isChannel(chat)) {
                            inputPeer = new TLRPC.TL_inputPeerChannel();
                            inputPeer.channel_id = -uid;
                        } else {
                            inputPeer = new TLRPC.TL_inputPeerChat();
                            inputPeer.chat_id = -uid;
                        }
                        req.ids.add(inputPeer);
                    }
                }
            } else {
                req.sources = participantsToLoad;
            }
            req.offset = "";
            req.limit = 100;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!loadingGuids.remove(guid)) {
                    return;
                }
                if (response != null) {
                    TLRPC.TL_phone_groupParticipants groupParticipants = (TLRPC.TL_phone_groupParticipants) response;
                    currentAccount.getMessagesController().putUsers(groupParticipants.users, false);
                    currentAccount.getMessagesController().putChats(groupParticipants.chats, false);
                    for (int a = 0, N = groupParticipants.participants.size(); a < N; a++) {
                        TLRPC.TL_groupCallParticipant participant = groupParticipants.participants.get(a);
                        int pid = MessageObject.getPeerId(participant.peer);
                        TLRPC.TL_groupCallParticipant oldParticipant = participants.get(pid);
                        if (oldParticipant != null) {
                            sortedParticipants.remove(oldParticipant);
                            processAllSources(oldParticipant, false);
                        }
                        participants.put(pid, participant);
                        sortedParticipants.add(participant);
                        processAllSources(participant, true);
                        if (invitedUsersMap.contains(pid)) {
                            Integer id = pid;
                            invitedUsersMap.remove(id);
                            invitedUsers.remove(id);
                        }
                    }
                    if (call.participants_count < participants.size()) {
                        call.participants_count = participants.size();
                    }
                    sortParticipants();
                    currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
                    if (onLoad != null) {
                        onLoad.onLoad(participantsToLoad);
                    } else {
                        setParticiapantsVolume();
                    }
                }
                set.removeAll(participantsToLoad);
            }));
        }

        private void processAllSources(TLRPC.TL_groupCallParticipant participant, boolean add) {
            if (participant.source != 0) {
                if (add) {
                    participantsBySources.put(participant.source, participant);
                } else {
                    participantsBySources.remove(participant.source);
                }
            }
            for (int c = 0; c < 2; c++) {
                TLRPC.TL_groupCallParticipantVideo data = c == 0 ? participant.video : participant.presentation;
                if (data != null) {
                    SparseArray<TLRPC.TL_groupCallParticipant> sourcesArray = c == 0 ? participantsByVideoSources : participantsByPresentationSources;
                    for (int a = 0, N = data.source_groups.size(); a < N; a++) {
                        TLRPC.TL_groupCallParticipantVideoSourceGroup sourceGroup = data.source_groups.get(a);
                        for (int b = 0, N2 = sourceGroup.sources.size(); b < N2; b++) {
                            int source = sourceGroup.sources.get(b);
                            if (add) {
                                sourcesArray.put(source, participant);
                            } else {
                                sourcesArray.remove(source);
                            }
                        }
                    }
                    if (add) {
                        if (c == 0) {
                            participant.videoEndpoint = data.endpoint;
                        } else {
                            participant.presentationEndpoint = data.endpoint;
                        }
                    } else {
                        if (c == 0) {
                            participant.videoEndpoint = null;
                        } else {
                            participant.presentationEndpoint = null;
                        }
                    }
                }
            }
        }

        public void processVoiceLevelsUpdate(int[] ssrc, float[] levels, boolean[] voice) {
            boolean updated = false;
            boolean updateCurrentSpeakingList = false;
            int currentTime = currentAccount.getConnectionsManager().getCurrentTime();
            ArrayList<Integer> participantsToLoad = null;
            long time = SystemClock.elapsedRealtime();
            long uptime = SystemClock.uptimeMillis();
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
            for (int a = 0; a < ssrc.length; a++) {
                TLRPC.TL_groupCallParticipant participant;
                if (ssrc[a] == 0) {
                    participant = participants.get(getSelfId());
                } else {
                    participant = participantsBySources.get(ssrc[a]);
                }
                if (participant != null) {
                    participant.hasVoice = voice[a];
                    if (voice[a] || time - participant.lastVoiceUpdateTime > 500) {
                        participant.hasVoiceDelayed = voice[a];
                        participant.lastVoiceUpdateTime = time;
                    }
                    int peerId = MessageObject.getPeerId(participant.peer);
                    if (levels[a] > 0.1f) {
                        if (voice[a] && participant.lastTypingDate + 1 < currentTime) {
                            if (time != participant.lastVisibleDate) {
                                participant.active_date = currentTime;
                            }
                            participant.lastTypingDate = currentTime;
                            updated = true;
                        }
                        participant.lastSpeakTime = uptime;
                        participant.amplitude = levels[a];

                        if (currentSpeakingPeers.get(peerId, null) == null) {
                            currentSpeakingPeers.put(peerId, participant);
                            updateCurrentSpeakingList = true;
                        }
                    } else {
                        if (uptime - participant.lastSpeakTime >= 500) {
                            if (currentSpeakingPeers.get(peerId, null) != null) {
                                currentSpeakingPeers.remove(peerId);
                                updateCurrentSpeakingList = true;
                            }
                        }
                        participant.amplitude = 0;
                    }
                } else if (ssrc[a] != 0) {
                    if (participantsToLoad == null) {
                        participantsToLoad = new ArrayList<>();
                    }
                    participantsToLoad.add(ssrc[a]);
                }
            }
            if (participantsToLoad != null) {
                loadUnknownParticipants(participantsToLoad, false, null);
            }
            if (updated) {
                sortParticipants();
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
            }
            if (updateCurrentSpeakingList) {
                if (currentSpeakingPeers.size() > 0) {
                    AndroidUtilities.cancelRunOnUIThread(updateCurrentSpeakingRunnable);
                    AndroidUtilities.runOnUIThread(updateCurrentSpeakingRunnable, 550);
                }
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallSpeakingUsersUpdated, chatId, call.id, false);
            }
        }

        public void updateVisibleParticipants() {
            sortParticipants();
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false, 0L);
        }

        public void clearVideFramesInfo() {
            for (int i = 0; i < sortedParticipants.size(); i++) {
                sortedParticipants.get(i).hasCameraFrame = VIDEO_FRAME_NO_FRAME;
                sortedParticipants.get(i).hasPresentationFrame = VIDEO_FRAME_NO_FRAME;
                sortedParticipants.get(i).videoIndex = 0;
            }
            sortParticipants();
        }

        public interface OnParticipantsLoad {
            void onLoad(ArrayList<Integer> ssrcs);
        }

        public void processUnknownVideoParticipants(int[] ssrc, OnParticipantsLoad onLoad) {
            ArrayList<Integer> participantsToLoad = null;
            for (int a = 0; a < ssrc.length; a++) {
                if (participantsBySources.get(ssrc[a]) != null || participantsByVideoSources.get(ssrc[a]) != null || participantsByPresentationSources.get(ssrc[a]) != null) {
                    continue;
                }
                if (participantsToLoad == null) {
                    participantsToLoad = new ArrayList<>();
                }
                participantsToLoad.add(ssrc[a]);
            }
            if (participantsToLoad != null) {
                loadUnknownParticipants(participantsToLoad, false, onLoad);
            } else {
                onLoad.onLoad(null);
            }
        }

        private int isValidUpdate(TLRPC.TL_updateGroupCallParticipants update) {
            if (call.version + 1 == update.version || call.version == update.version) {
                return 0;
            } else if (call.version < update.version) {
                return 1;
            } else {
                return 2;
            }
        }

        public void setSelfPeer(TLRPC.InputPeer peer) {
            if (peer == null) {
                selfPeer = null;
            } else {
                if (peer instanceof TLRPC.TL_inputPeerUser) {
                    selfPeer = new TLRPC.TL_peerUser();
                    selfPeer.user_id = peer.user_id;
                } else if (peer instanceof TLRPC.TL_inputPeerChat) {
                    selfPeer = new TLRPC.TL_peerChat();
                    selfPeer.chat_id = peer.chat_id;
                } else {
                    selfPeer = new TLRPC.TL_peerChannel();
                    selfPeer.channel_id = peer.channel_id;
                }
            }
        }

        private void processUpdatesQueue() {
            Collections.sort(updatesQueue, (updates, updates2) -> AndroidUtilities.compare(updates.version, updates2.version));
            if (updatesQueue != null && !updatesQueue.isEmpty()) {
                boolean anyProceed = false;
                for (int a = 0; a < updatesQueue.size(); a++) {
                    TLRPC.TL_updateGroupCallParticipants update = updatesQueue.get(a);
                    int updateState = isValidUpdate(update);
                    if (updateState == 0) {
                        processParticipantsUpdate(update, true);
                        anyProceed = true;
                        updatesQueue.remove(a);
                        a--;
                    } else if (updateState == 1) {
                        if (updatesStartWaitTime != 0 && (anyProceed || Math.abs(System.currentTimeMillis() - updatesStartWaitTime) <= 1500)) {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("HOLE IN GROUP CALL UPDATES QUEUE - will wait more time");
                            }
                            if (anyProceed) {
                                updatesStartWaitTime = System.currentTimeMillis();
                            }
                        } else {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("HOLE IN GROUP CALL UPDATES QUEUE - reload participants");
                            }
                            updatesStartWaitTime = 0;
                            updatesQueue.clear();
                            nextLoadOffset = null;
                            loadMembers(true);
                        }
                        return;
                    } else {
                        updatesQueue.remove(a);
                        a--;
                    }
                }
                updatesQueue.clear();
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("GROUP CALL UPDATES QUEUE PROCEED - OK");
                }
            }
            updatesStartWaitTime = 0;
        }

        private void checkQueue() {
            checkQueueRunnable = null;
            if (updatesStartWaitTime != 0 && (System.currentTimeMillis() - updatesStartWaitTime) >= 1500) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("QUEUE GROUP CALL UPDATES WAIT TIMEOUT - CHECK QUEUE");
                }
                processUpdatesQueue();
            }
            if (!updatesQueue.isEmpty()) {
                AndroidUtilities.runOnUIThread(checkQueueRunnable = this::checkQueue, 1000);
            }
        }

        private void loadGroupCall() {
            if (loadingGroupCall || SystemClock.elapsedRealtime() - lastGroupCallReloadTime < 30000) {
                return;
            }
            loadingGroupCall = true;
            TLRPC.TL_phone_getGroupParticipants req = new TLRPC.TL_phone_getGroupParticipants();
            req.call = getInputGroupCall();
            req.offset = "";
            req.limit = 1;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                lastGroupCallReloadTime = SystemClock.elapsedRealtime();
                loadingGroupCall = false;
                if (response != null) {
                    TLRPC.TL_phone_groupParticipants res = (TLRPC.TL_phone_groupParticipants) response;
                    currentAccount.getMessagesController().putUsers(res.users, false);
                    currentAccount.getMessagesController().putChats(res.chats, false);
                    if (call.participants_count != res.count) {
                        call.participants_count = res.count;
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("new participants reload count " + call.participants_count);
                        }
                        currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
                    }
                }
            }));
        }

        public void processParticipantsUpdate(TLRPC.TL_updateGroupCallParticipants update, boolean fromQueue) {
            if (!fromQueue) {
                boolean versioned = false;
                for (int a = 0, N = update.participants.size(); a < N; a++) {
                    TLRPC.TL_groupCallParticipant participant = update.participants.get(a);
                    if (participant.versioned) {
                        versioned = true;
                        break;
                    }
                }
                if (versioned && call.version + 1 < update.version) {
                    if (reloadingMembers || updatesStartWaitTime == 0 || Math.abs(System.currentTimeMillis() - updatesStartWaitTime) <= 1500) {
                        if (updatesStartWaitTime == 0) {
                            updatesStartWaitTime = System.currentTimeMillis();
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("add TL_updateGroupCallParticipants to queue " + update.version);
                        }
                        updatesQueue.add(update);
                        if (checkQueueRunnable == null) {
                            AndroidUtilities.runOnUIThread(checkQueueRunnable = this::checkQueue, 1500);
                        }
                    } else {
                        nextLoadOffset = null;
                        loadMembers(true);
                    }
                    return;
                }
                if (versioned && update.version < call.version) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("ignore processParticipantsUpdate because of version");
                    }
                    return;
                }
            }
            boolean reloadCall = false;
            boolean updated = false;
            boolean selfUpdated = false;
            boolean changedOrAdded = false;
            boolean speakingUpdated = false;

            int selfId = getSelfId();
            long time = SystemClock.elapsedRealtime();
            long justJoinedId = 0;
            int lastParticipantDate;
            if (!sortedParticipants.isEmpty()) {
                lastParticipantDate = sortedParticipants.get(sortedParticipants.size() - 1).date;
            } else {
                lastParticipantDate = 0;
            }
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
            for (int a = 0, N = update.participants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant participant = update.participants.get(a);
                int pid = MessageObject.getPeerId(participant.peer);
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("process participant " + pid + " left = " + participant.left + " versioned " + participant.versioned + " flags = " + participant.flags + " self = " + selfId + " volume = " + participant.volume);
                }
                TLRPC.TL_groupCallParticipant oldParticipant = participants.get(pid);
                if (participant.left) {
                    if (oldParticipant == null && update.version == call.version) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("unknowd participant left, reload call");
                        }
                        reloadCall = true;
                    }
                    if (oldParticipant != null) {
                        participants.remove(pid);
                        processAllSources(oldParticipant, false);
                        sortedParticipants.remove(oldParticipant);
                        visibleParticipants.remove(oldParticipant);
                        if (currentSpeakingPeers.get(pid, null) != null) {
                            currentSpeakingPeers.remove(pid);
                            speakingUpdated = true;
                        }
                        for (int i = 0; i < visibleVideoParticipants.size(); i++) {
                            VideoParticipant videoParticipant = visibleVideoParticipants.get(i);
                            if (MessageObject.getPeerId(videoParticipant.participant.peer) == MessageObject.getPeerId(oldParticipant.peer)) {
                                visibleVideoParticipants.remove(i);
                                i--;
                            }
                        }
                    }
                    call.participants_count--;
                    if (call.participants_count < 0) {
                        call.participants_count = 0;
                    }
                    updated = true;
                } else {
                    if (invitedUsersMap.contains(pid)) {
                        Integer id = pid;
                        invitedUsersMap.remove(id);
                        invitedUsers.remove(id);
                    }
                    if (oldParticipant != null) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("new participant, update old");
                        }
                        oldParticipant.muted = participant.muted;
                        if (participant.muted && currentSpeakingPeers.get(pid, null) != null) {
                            currentSpeakingPeers.remove(pid);
                            speakingUpdated = true;
                        }
                        if (!participant.min) {
                            oldParticipant.volume = participant.volume;
                            oldParticipant.muted_by_you = participant.muted_by_you;
                        } else {
                            if ((participant.flags & 128) != 0 && (oldParticipant.flags & 128) == 0) {
                                participant.flags &=~ 128;
                            }
                            if (participant.volume_by_admin && oldParticipant.volume_by_admin) {
                                oldParticipant.volume = participant.volume;
                            }
                        }
                        oldParticipant.flags = participant.flags;
                        oldParticipant.can_self_unmute = participant.can_self_unmute;
                        oldParticipant.video_joined = participant.video_joined;
                        if (oldParticipant.raise_hand_rating == 0 && participant.raise_hand_rating != 0) {
                            oldParticipant.lastRaiseHandDate = SystemClock.elapsedRealtime();
                        }
                        oldParticipant.raise_hand_rating = participant.raise_hand_rating;
                        oldParticipant.date = participant.date;
                        oldParticipant.lastTypingDate = Math.max(oldParticipant.active_date, participant.active_date);
                        if (time != oldParticipant.lastVisibleDate) {
                            oldParticipant.active_date = oldParticipant.lastTypingDate;
                        }
                        if (oldParticipant.source != participant.source || !isSameVideo(oldParticipant.video, participant.video) || !isSameVideo(oldParticipant.presentation, participant.presentation)) {
                            processAllSources(oldParticipant, false);
                            oldParticipant.video = participant.video;
                            oldParticipant.presentation = participant.presentation;
                            oldParticipant.source = participant.source;
                            processAllSources(oldParticipant, true);
                            participant.presentationEndpoint = oldParticipant.presentationEndpoint;
                            participant.videoEndpoint = oldParticipant.videoEndpoint;
                            participant.videoIndex = oldParticipant.videoIndex;
                        } else if (oldParticipant.video != null && participant.video != null) {
                            oldParticipant.video.paused = participant.video.paused;
                        }
                    } else {
                        if (participant.just_joined) {
                            if (pid != selfId) {
                                justJoinedId = pid;
                            }
                            call.participants_count++;
                            if (update.version == call.version) {
                                reloadCall = true;
                                if (BuildVars.LOGS_ENABLED) {
                                    FileLog.d("new participant, just joined, reload call");
                                }
                            } else {
                                if (BuildVars.LOGS_ENABLED) {
                                    FileLog.d("new participant, just joined");
                                }
                            }
                        }
                        if (participant.raise_hand_rating != 0) {
                            participant.lastRaiseHandDate = SystemClock.elapsedRealtime();
                        }
                        if (pid == selfId || sortedParticipants.size() < 20 || participant.date <= lastParticipantDate || participant.active_date != 0 || participant.can_self_unmute || !participant.muted || !participant.min || membersLoadEndReached) {
                            sortedParticipants.add(participant);
                        }
                        participants.put(pid, participant);
                        processAllSources(participant, true);
                    }
                    if (pid == selfId && participant.active_date == 0 && (participant.can_self_unmute || !participant.muted)) {
                        participant.active_date = currentAccount.getConnectionsManager().getCurrentTime();
                    }
                    changedOrAdded = true;
                    updated = true;
                }
                if (pid == selfId) {
                    selfUpdated = true;
                }
            }
            if (update.version > call.version) {
                call.version = update.version;
                if (!fromQueue) {
                    processUpdatesQueue();
                }
            }
            if (call.participants_count < participants.size()) {
                call.participants_count = participants.size();
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("new participants count after update " + call.participants_count);
            }
            if (reloadCall) {
                loadGroupCall();
            }
            if (updated) {
                if (changedOrAdded) {
                    sortParticipants();
                }
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, selfUpdated, justJoinedId);
            }
            if (speakingUpdated) {
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallSpeakingUsersUpdated, chatId, call.id, false);
            }
        }

        private boolean isSameVideo(TLRPC.TL_groupCallParticipantVideo oldVideo, TLRPC.TL_groupCallParticipantVideo newVideo) {
            if (oldVideo == null && newVideo != null || oldVideo != null && newVideo == null) {
                return false;
            }
            if (oldVideo == null || newVideo == null) {
                return true;
            }
            if (!TextUtils.equals(oldVideo.endpoint, newVideo.endpoint)) {
                return false;
            }
            if (oldVideo.source_groups.size() != newVideo.source_groups.size()) {
                return false;
            }
            for (int a = 0, N = oldVideo.source_groups.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipantVideoSourceGroup oldGroup = oldVideo.source_groups.get(a);
                TLRPC.TL_groupCallParticipantVideoSourceGroup newGroup = newVideo.source_groups.get(a);
                if (!TextUtils.equals(oldGroup.semantics, newGroup.semantics)) {
                    return false;
                }
                if (oldGroup.sources.size() != newGroup.sources.size()) {
                    return false;
                }
                for (int b = 0, N2 = oldGroup.sources.size(); b < N2; b++) {
                    if (!newGroup.sources.contains(oldGroup.sources.get(b))) {
                        return false;
                    }
                }
            }
            return true;
        }

        public void processGroupCallUpdate(AccountInstance accountInstance, TLRPC.TL_updateGroupCall update) {
            if (call.version < update.call.version) {
                nextLoadOffset = null;
                loadMembers(true);
            }
            call = update.call;
            TLRPC.TL_groupCallParticipant selfParticipant = participants.get(getSelfId());
            recording = call.record_start_date != 0;
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
        }

        public TLRPC.TL_inputGroupCall getInputGroupCall() {
            TLRPC.TL_inputGroupCall inputGroupCall = new TLRPC.TL_inputGroupCall();
            inputGroupCall.id = call.id;
            inputGroupCall.access_hash = call.access_hash;
            return inputGroupCall;
        }

        public static boolean videoIsActive(TLRPC.TL_groupCallParticipant participant, boolean presentation, ChatObject.Call call) {
            VoIPService service = VoIPService.getSharedInstance();
            if (service == null) {
                return false;
            }
            if (participant.self) {
                return service.getVideoState(presentation) == Instance.VIDEO_STATE_ACTIVE;
            } else {
                if (call.videoNotAvailableParticipant != null && call.videoNotAvailableParticipant.participant == participant || call.participants.get(MessageObject.getPeerId(participant.peer)) != null) {
                    if (presentation) {
                        return participant.presentation != null;// && participant.hasPresentationFrame == 2;
                    } else {
                        return participant.video != null;// && participant.hasCameraFrame == 2;
                    }
                } else {
                    return false;
                }
            }
        }

        public void sortParticipants() {
            visibleVideoParticipants.clear();
            visibleParticipants.clear();
            TLRPC.Chat chat = currentAccount.getMessagesController().getChat(chatId);
            boolean isAdmin = ChatObject.canManageCalls(chat);
            int selfId = getSelfId();
            VoIPService service = VoIPService.getSharedInstance();
            TLRPC.TL_groupCallParticipant selfParticipant = participants.get(selfId);
            canStreamVideo = selfParticipant != null && selfParticipant.video_joined;
            boolean allowedVideoCount;
            boolean hasAnyVideo = false;
            for (int i = 0; i < sortedParticipants.size(); i++) {
                TLRPC.TL_groupCallParticipant participant = sortedParticipants.get(i);
                if (videoIsActive(participant, false, this) || videoIsActive(participant, true, this)) {
                    hasAnyVideo = true;
                    if (canStreamVideo) {
                        if (participant.videoIndex == 0) {
                            if (participant.self) {
                                participant.videoIndex = Integer.MAX_VALUE;
                            } else {
                                participant.videoIndex = ++videoPointer;
                            }
                        }
                    } else {
                        participant.videoIndex = 0;
                    }
                } else if (participant.self || !canStreamVideo || (participant.video == null && participant.presentation == null)) {
                    participant.videoIndex = 0;
                }
            }

            Comparator<TLRPC.TL_groupCallParticipant> comparator = (o1, o2) -> {
                boolean videoActive1 = o1.videoIndex > 0;
                boolean videoActive2 = o2.videoIndex > 0;
                if (videoActive1 && videoActive2) {
                    return o2.videoIndex - o1.videoIndex;
                } else if (videoActive1) {
                    return -1;
                } else if (videoActive2) {
                    return 1;
                }
                if (o1.active_date != 0 && o2.active_date != 0) {
                    return Integer.compare(o2.active_date, o1.active_date);
                } else if (o1.active_date != 0) {
                    return -1;
                } else if (o2.active_date != 0) {
                    return 1;
                }
                if (MessageObject.getPeerId(o1.peer) == selfId) {
                    return -1;
                } else if (MessageObject.getPeerId(o2.peer) == selfId) {
                    return 1;
                }
                if (isAdmin) {
                    if (o1.raise_hand_rating != 0 && o2.raise_hand_rating != 0) {
                        return Long.compare(o2.raise_hand_rating, o1.raise_hand_rating);
                    } else if (o1.raise_hand_rating != 0) {
                        return -1;
                    } else if (o2.raise_hand_rating != 0) {
                        return 1;
                    }
                }
                if (call.join_date_asc) {
                    return Integer.compare(o1.date, o2.date);
                } else {
                    return Integer.compare(o2.date, o1.date);
                }
            };
            Collections.sort(sortedParticipants, comparator);

            if (sortedParticipants.size() > MAX_PARTICIPANTS_COUNT && (!ChatObject.canManageCalls(chat) || sortedParticipants.get(sortedParticipants.size() - 1).raise_hand_rating == 0)) {
                for (int a = MAX_PARTICIPANTS_COUNT, N = sortedParticipants.size(); a < N; a++) {
                    TLRPC.TL_groupCallParticipant p = sortedParticipants.get(MAX_PARTICIPANTS_COUNT);
                    if (p.raise_hand_rating != 0) {
                        continue;
                    }
                    processAllSources(p, false);
                    participants.remove(MessageObject.getPeerId(p.peer));
                    sortedParticipants.remove(MAX_PARTICIPANTS_COUNT);
                }
            }
            checkOnlineParticipants();

            if (!canStreamVideo && hasAnyVideo) {
                visibleVideoParticipants.add(videoNotAvailableParticipant);
            }

            int wideVideoIndex = 0;
            for (int i = 0; i < sortedParticipants.size(); i++) {
                TLRPC.TL_groupCallParticipant participant = sortedParticipants.get(i);
                if (canStreamVideo && participant.videoIndex != 0) {
                    if (!participant.self && videoIsActive(participant, true, this) && videoIsActive(participant, false, this)) {
                        VideoParticipant videoParticipant = videoParticipantsCache.get(participant.videoEndpoint);
                        if (videoParticipant == null) {
                            videoParticipant = new VideoParticipant(participant, false, true);
                            videoParticipantsCache.put(participant.videoEndpoint, videoParticipant);
                        } else {
                            videoParticipant.participant = participant;
                            videoParticipant.presentation = false;
                            videoParticipant.hasSame = true;
                        }

                        VideoParticipant presentationParticipant = videoParticipantsCache.get(participant.presentationEndpoint);
                        if (presentationParticipant == null) {
                            presentationParticipant = new VideoParticipant(participant, true, true);
                        } else {
                            presentationParticipant.participant = participant;
                            presentationParticipant.presentation = true;
                            presentationParticipant.hasSame = true;
                        }
                        visibleVideoParticipants.add(videoParticipant);
                        if (videoParticipant.aspectRatio > 1f) {
                            wideVideoIndex = visibleVideoParticipants.size() - 1;
                        }
                        visibleVideoParticipants.add(presentationParticipant);
                        if (presentationParticipant.aspectRatio > 1f) {
                            wideVideoIndex = visibleVideoParticipants.size() - 1;
                        }
                    } else {
                        if (participant.self) {
                            if (videoIsActive(participant, true, this)) {
                                visibleVideoParticipants.add(new VideoParticipant(participant, true, false));
                            }
                            if (videoIsActive(participant, false, this)) {
                                visibleVideoParticipants.add(new VideoParticipant(participant, false, false));
                            }
                        } else {
                            boolean presentation = videoIsActive(participant, true, this);

                            VideoParticipant videoParticipant = videoParticipantsCache.get(presentation ? participant.presentationEndpoint : participant.videoEndpoint);
                            if (videoParticipant == null) {
                                videoParticipant = new VideoParticipant(participant, presentation, false);
                                videoParticipantsCache.put(presentation ? participant.presentationEndpoint : participant.videoEndpoint, videoParticipant);
                            } else {
                                videoParticipant.participant = participant;
                                videoParticipant.presentation = presentation;
                                videoParticipant.hasSame = false;
                            }
                            visibleVideoParticipants.add(videoParticipant);
                            if (videoParticipant.aspectRatio > 1f) {
                                wideVideoIndex = visibleVideoParticipants.size() - 1;
                            }
                        }
                    }
                } else {
                    visibleParticipants.add(participant);
                }
            }

            if (!GroupCallActivity.isLandscapeMode && visibleVideoParticipants.size() % 2 == 1) {
                VideoParticipant videoParticipant = visibleVideoParticipants.remove(wideVideoIndex);
                visibleVideoParticipants.add(videoParticipant);
            }
        }

        public void saveActiveDates() {
            for (int a = 0, N = sortedParticipants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant p = sortedParticipants.get(a);
                p.lastActiveDate = p.active_date;
            }
        }

        private void checkOnlineParticipants() {
            if (typingUpdateRunnableScheduled) {
                AndroidUtilities.cancelRunOnUIThread(typingUpdateRunnable);
                typingUpdateRunnableScheduled = false;
            }
            speakingMembersCount = 0;
            int currentTime = currentAccount.getConnectionsManager().getCurrentTime();
            int minDiff = Integer.MAX_VALUE;
            for (int a = 0, N = sortedParticipants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant participant = sortedParticipants.get(a);
                int diff = currentTime - participant.active_date;
                if (diff < 5) {
                    speakingMembersCount++;
                    minDiff = Math.min(diff, minDiff);
                }
                if (Math.max(participant.date, participant.active_date) <= currentTime - 5) {
                    break;
                }
            }
            if (minDiff != Integer.MAX_VALUE) {
                AndroidUtilities.runOnUIThread(typingUpdateRunnable, minDiff * 1000);
                typingUpdateRunnableScheduled = true;
            }
        }

        public void toggleRecord(String title) {
            recording = !recording;
            TLRPC.TL_phone_toggleGroupCallRecord req = new TLRPC.TL_phone_toggleGroupCallRecord();
            req.call = getInputGroupCall();
            req.start = recording;
            if (title != null) {
                req.title = title;
                req.flags |= 2;
            }
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> {
                if (response != null) {
                    final TLRPC.Updates res = (TLRPC.Updates) response;
                    currentAccount.getMessagesController().processUpdates(res, false);
                }
            });
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
        }
    }

    public static int getParticipantVolume(TLRPC.TL_groupCallParticipant participant) {
        return ((participant.flags & 128) != 0 ? participant.volume : 10000);
    }

    private static boolean isBannableAction(int action) {
        switch (action) {
            case ACTION_PIN:
            case ACTION_CHANGE_INFO:
            case ACTION_INVITE:
            case ACTION_SEND:
            case ACTION_SEND_MEDIA:
            case ACTION_SEND_STICKERS:
            case ACTION_EMBED_LINKS:
            case ACTION_SEND_POLLS:
            case ACTION_VIEW:
                return true;
        }
        return false;
    }

    private static boolean isAdminAction(int action) {
        switch (action) {
            case ACTION_PIN:
            case ACTION_CHANGE_INFO:
            case ACTION_INVITE:
            case ACTION_ADD_ADMINS:
            case ACTION_POST:
            case ACTION_EDIT_MESSAGES:
            case ACTION_DELETE_MESSAGES:
            case ACTION_BLOCK_USERS:
                return true;
        }
        return false;
    }

    private static boolean getBannedRight(TLRPC.TL_chatBannedRights rights, int action) {
        if (rights == null) {
            return false;
        }
        boolean value;
        switch (action) {
            case ACTION_PIN:
                return rights.pin_messages;
            case ACTION_CHANGE_INFO:
                return rights.change_info;
            case ACTION_INVITE:
                return rights.invite_users;
            case ACTION_SEND:
                return rights.send_messages;
            case ACTION_SEND_MEDIA:
                return rights.send_media;
            case ACTION_SEND_STICKERS:
                return rights.send_stickers;
            case ACTION_EMBED_LINKS:
                return rights.embed_links;
            case ACTION_SEND_POLLS:
                return rights.send_polls;
            case ACTION_VIEW:
                return rights.view_messages;
        }
        return false;
    }

    public static boolean isActionBannedByDefault(TLRPC.Chat chat, int action) {
        if (getBannedRight(chat.banned_rights, action)) {
            return false;
        }
        return getBannedRight(chat.default_banned_rights, action);
    }

    public static boolean isActionBanned(TLRPC.Chat chat, int action) {
        return chat != null && (getBannedRight(chat.banned_rights, action) || getBannedRight(chat.default_banned_rights, action));
    }

    public static boolean canUserDoAdminAction(TLRPC.Chat chat, int action) {
        if (chat == null) {
            return false;
        }
        if (chat.creator) {
            return true;
        }
        if (chat.admin_rights != null) {
            boolean value;
            switch (action) {
                case ACTION_PIN:
                    value = chat.admin_rights.pin_messages;
                    break;
                case ACTION_CHANGE_INFO:
                    value = chat.admin_rights.change_info;
                    break;
                case ACTION_INVITE:
                    value = chat.admin_rights.invite_users;
                    break;
                case ACTION_ADD_ADMINS:
                    value = chat.admin_rights.add_admins;
                    break;
                case ACTION_POST:
                    value = chat.admin_rights.post_messages;
                    break;
                case ACTION_EDIT_MESSAGES:
                    value = chat.admin_rights.edit_messages;
                    break;
                case ACTION_DELETE_MESSAGES:
                    value = chat.admin_rights.delete_messages;
                    break;
                case ACTION_BLOCK_USERS:
                    value = chat.admin_rights.ban_users;
                    break;
                case ACTION_MANAGE_CALLS:
                    value = chat.admin_rights.manage_call;
                    break;
                default:
                    value = false;
                    break;
            }
            if (value) {
                return true;
            }
        }
        return false;
    }

    public static boolean canUserDoAction(TLRPC.Chat chat, int action) {
        if (chat == null) {
            return true;
        }
        if (canUserDoAdminAction(chat, action)) {
            return true;
        }
        if (getBannedRight(chat.banned_rights, action)) {
            return false;
        }
        if (isBannableAction(action)) {
            if (chat.admin_rights != null && !isAdminAction(action)) {
                return true;
            }
            if (chat.default_banned_rights == null && (
                    chat instanceof TLRPC.TL_chat_layer92 ||
                    chat instanceof TLRPC.TL_chat_old ||
                    chat instanceof TLRPC.TL_chat_old2 ||
                    chat instanceof TLRPC.TL_channel_layer92 ||
                    chat instanceof TLRPC.TL_channel_layer77 ||
                    chat instanceof TLRPC.TL_channel_layer72 ||
                    chat instanceof TLRPC.TL_channel_layer67 ||
                    chat instanceof TLRPC.TL_channel_layer48 ||
                    chat instanceof TLRPC.TL_channel_old)) {
                return true;
            }
            return chat.default_banned_rights != null && !getBannedRight(chat.default_banned_rights, action);
        }
        return false;
    }

    public static boolean isLeftFromChat(TLRPC.Chat chat) {
        return chat == null || chat instanceof TLRPC.TL_chatEmpty || chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden || chat.left || chat.deactivated;
    }

    public static boolean isKickedFromChat(TLRPC.Chat chat) {
        return chat == null || chat instanceof TLRPC.TL_chatEmpty || chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden || chat.kicked || chat.deactivated || chat.banned_rights != null && chat.banned_rights.view_messages;
    }

    public static boolean isNotInChat(TLRPC.Chat chat) {
        return chat == null || chat instanceof TLRPC.TL_chatEmpty || chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden || chat.left || chat.kicked || chat.deactivated;
    }

    public static boolean isChannel(TLRPC.Chat chat) {
        return chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden;
    }

    public static boolean isMegagroup(TLRPC.Chat chat) {
        return (chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden) && chat.megagroup;
    }

    public static boolean isMegagroup(int currentAccount, int chatId) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        return ChatObject.isChannel(chat) && chat.megagroup;
    }

    public static boolean hasAdminRights(TLRPC.Chat chat) {
        return chat != null && (chat.creator || chat.admin_rights != null && chat.admin_rights.flags != 0);
    }

    public static boolean canChangeChatInfo(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_CHANGE_INFO);
    }

    public static boolean canAddAdmins(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_ADD_ADMINS);
    }

    public static boolean canBlockUsers(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_BLOCK_USERS);
    }

    public static boolean canManageCalls(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_MANAGE_CALLS);
    }

    public static boolean canSendStickers(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND_STICKERS);
    }

    public static boolean canSendEmbed(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_EMBED_LINKS);
    }

    public static boolean canSendMedia(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND_MEDIA);
    }

    public static boolean canSendPolls(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND_POLLS);
    }

    public static boolean canSendMessages(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND);
    }

    public static boolean canPost(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_POST);
    }

    public static boolean canAddUsers(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_INVITE);
    }

    public static boolean shouldSendAnonymously(TLRPC.Chat chat) {
        return chat != null && chat.admin_rights != null && chat.admin_rights.anonymous;
    }

    public static boolean canAddBotsToChat(TLRPC.Chat chat) {
        if (isChannel(chat)) {
            return chat.megagroup && (chat.admin_rights != null && (chat.admin_rights.post_messages || chat.admin_rights.add_admins) || chat.creator);
        } else {
            return chat.migrated_to == null;
        }
    }

    public static boolean canPinMessages(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_PIN) || ChatObject.isChannel(chat) && !chat.megagroup && chat.admin_rights != null && chat.admin_rights.edit_messages;
    }

    public static boolean isChannel(int chatId, int currentAccount) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        return chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden;
    }

    public static boolean isCanWriteToChannel(int chatId, int currentAccount) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        return ChatObject.canSendMessages(chat) || chat.megagroup;
    }

    public static boolean canWriteToChat(TLRPC.Chat chat) {
        return !isChannel(chat) || chat.creator || chat.admin_rights != null && chat.admin_rights.post_messages || !chat.broadcast && !chat.gigagroup || chat.gigagroup && ChatObject.hasAdminRights(chat);
    }

    public static String getBannedRightsString(TLRPC.TL_chatBannedRights bannedRights) {
        String currentBannedRights = "";
        currentBannedRights += bannedRights.view_messages ? 1 : 0;
        currentBannedRights += bannedRights.send_messages ? 1 : 0;
        currentBannedRights += bannedRights.send_media ? 1 : 0;
        currentBannedRights += bannedRights.send_stickers ? 1 : 0;
        currentBannedRights += bannedRights.send_gifs ? 1 : 0;
        currentBannedRights += bannedRights.send_games ? 1 : 0;
        currentBannedRights += bannedRights.send_inline ? 1 : 0;
        currentBannedRights += bannedRights.embed_links ? 1 : 0;
        currentBannedRights += bannedRights.send_polls ? 1 : 0;
        currentBannedRights += bannedRights.invite_users ? 1 : 0;
        currentBannedRights += bannedRights.change_info ? 1 : 0;
        currentBannedRights += bannedRights.pin_messages ? 1 : 0;
        currentBannedRights += bannedRights.until_date;
        return currentBannedRights;
    }

    public static TLRPC.Chat getChatByDialog(long did, int currentAccount) {
        int lower_id = (int) did;
        int high_id = (int) (did >> 32);
        if (lower_id < 0) {
            return MessagesController.getInstance(currentAccount).getChat(-lower_id);
        }
        return null;
    }

    public static boolean hasPhoto(TLRPC.Chat chat) {
        return chat != null && chat.photo != null && !(chat.photo instanceof TLRPC.TL_chatPhotoEmpty);
    }

    public static TLRPC.ChatPhoto getPhoto(TLRPC.Chat chat) {
        return hasPhoto(chat) ? chat.photo : null;
    }

    public static class VideoParticipant {

        public TLRPC.TL_groupCallParticipant participant;
        public boolean presentation;
        public boolean hasSame;
        public float aspectRatio;// w / h

        public VideoParticipant(TLRPC.TL_groupCallParticipant participant, boolean presentation, boolean hasSame) {
            this.participant = participant;
            this.presentation = presentation;
            this.hasSame = hasSame;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            VideoParticipant that = (VideoParticipant) o;
            return presentation == that.presentation && MessageObject.getPeerId(participant.peer) == MessageObject.getPeerId(that.participant.peer);
        }

        public void setAspectRatio(float aspectRatio, Call call) {
            if (this.aspectRatio != aspectRatio) {
                this.aspectRatio = aspectRatio;
                if (!GroupCallActivity.isLandscapeMode && call.visibleVideoParticipants.size() % 2 == 1) {
                    call.updateVisibleParticipants();
                }
            }
        }
    }
}
