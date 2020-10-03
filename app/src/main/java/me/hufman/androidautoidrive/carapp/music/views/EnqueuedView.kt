package me.hufman.androidautoidrive.carapp.music.views

import de.bmw.idrive.BMWRemoting
import me.hufman.androidautoidrive.GraphicsHelpers
import me.hufman.androidautoidrive.UnicodeCleaner
import me.hufman.androidautoidrive.carapp.RHMIListAdapter
import me.hufman.androidautoidrive.carapp.music.MusicImageIDs
import me.hufman.androidautoidrive.music.MusicController
import me.hufman.androidautoidrive.music.MusicMetadata
import me.hufman.androidautoidrive.music.QueueMetadata
import me.hufman.idriveconnectionkit.rhmi.*
import kotlin.math.max

class EnqueuedView(val state: RHMIState, val musicController: MusicController, val graphicsHelpers: GraphicsHelpers, val musicImageIDs: MusicImageIDs) {
	companion object {
		//current default row width only supports 22 chars before rolling over
		private const val ROW_LINE_MAX_LENGTH = 22

		fun fits(state: RHMIState): Boolean {
			return state is RHMIState.PlainState &&
					state.componentsList.filterIsInstance<RHMIComponent.List>().isNotEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.Image>().isNotEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.Separator>().isEmpty()
		}
	}

	val listComponent: RHMIComponent.List
	val queueImageComponent: RHMIComponent.Image
	val titleLabelComponent: RHMIComponent.Label
	val subtitleLabelComponent: RHMIComponent.Label
	var currentSong: MusicMetadata? = null
	val songsList = ArrayList<MusicMetadata>()
	val songsEmptyList = RHMIModel.RaListModel.RHMIListConcrete(3)
	var queueMetadata: QueueMetadata? = null
	var currentlyVisibleRows: List<MusicMetadata> = emptyList()
	var currentVisibleRowsMusicMetadata: ArrayList<MusicMetadata> = ArrayList()
	var currentIndex: Int = 0

	var songsListAdapter = object: RHMIListAdapter<MusicMetadata>(4, songsList) {
		override fun convertRow(index: Int, item: MusicMetadata): Array<Any> {
			val checkmark = if (item.queueId == currentSong?.queueId) BMWRemoting.RHMIResourceIdentifier(BMWRemoting.RHMIResourceType.IMAGEID, musicImageIDs.CHECKMARK) else ""

			val coverArtImage = if (item.coverArt != null) graphicsHelpers.compress(item.coverArt!!, 90, 90, quality = 30) else ""

			var title = UnicodeCleaner.clean(item.title ?: "")
			if(title.length > ROW_LINE_MAX_LENGTH) {
				title = title.substring(0, 20) + "..."
			}

			val artist = UnicodeCleaner.clean(item.artist ?: "")

			val songMetaDataText = "${title}\n${artist}"

			return arrayOf(
					checkmark,
					coverArtImage,
					"",
					songMetaDataText
			)
		}
	}

	init {
		state as RHMIState.PlainState

		listComponent = state.componentsList.filterIsInstance<RHMIComponent.List>().first()

		queueImageComponent = state.componentsList.filterIsInstance<RHMIComponent.Image>().first()

		//all labels next to image at top of list don't scroll and are static
		titleLabelComponent = state.componentsList.filterIsInstance<RHMIComponent.Label>()[0]
		subtitleLabelComponent = state.componentsList.filterIsInstance<RHMIComponent.Label>()[1]

		songsEmptyList.addRow(arrayOf("", "", L.MUSIC_QUEUE_EMPTY))
	}

	fun initWidgets(playbackView: PlaybackView) {
		//this is required for pagination system
		listComponent.setProperty(RHMIProperty.PropertyId.VALID, false)

		listComponent.setVisible(true)
		listComponent.setProperty(RHMIProperty.PropertyId.LIST_COLUMNWIDTH, "57,90,10,*")
		listComponent.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = playbackView.state.id
		listComponent.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback { onClick(it) }
		listComponent.getSelectAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback { onSelectAction(it) }
	}

	fun show() {
		currentSong = musicController.getMetadata()
		val newQueueMetadata = musicController.getQueue()

		// same queue as before, just select currently playing song
		if(queueMetadata != null && queueMetadata == newQueueMetadata) {
			showCurrentlyPlayingSong()
			return
		}

		queueMetadata = newQueueMetadata
		songsList.clear()
		val songs = queueMetadata?.songs
		if (songs?.isNotEmpty() == true) {
			listComponent.setEnabled(true)
			listComponent.setSelectable(true)
			songsList.addAll(songs)

			listComponent.requestDataCallback = RequestDataCallback { startIndex, numRows ->
				showList(startIndex, numRows)

				val endIndex = if (startIndex+numRows >= songsList.size) songsList.size-1 else startIndex+numRows
				currentlyVisibleRows = songsListAdapter.realData.subList(startIndex,endIndex+1)
				currentVisibleRowsMusicMetadata.clear()
				currentlyVisibleRows.forEach { musicMetadata ->
					currentVisibleRowsMusicMetadata.add(MusicMetadata.copy(musicMetadata))
				}
			}

			showCurrentlyPlayingSong()
		} else {
			listComponent.setEnabled(false)
			listComponent.setSelectable(false)
			listComponent.getModel()?.setValue(songsEmptyList, 0, songsEmptyList.height, songsEmptyList.height)
		}

		val queueTitle = UnicodeCleaner.clean(queueMetadata?.title ?: "")
		val queueSubtitle = UnicodeCleaner.clean(queueMetadata?.subtitle ?: "")
		if (queueTitle.isBlank() && queueSubtitle.isBlank()) {
			state.getTextModel()?.asRaDataModel()?.value = "Now Playing"
		} else {
			state.getTextModel()?.asRaDataModel()?.value = "$queueTitle - $queueSubtitle"
		}

		if(queueMetadata?.coverArt != null) {
			queueImageComponent.getModel()?.asRaImageModel()?.value = graphicsHelpers.compress(musicController.getQueue()?.coverArt!!, 180, 180, quality = 60)
			titleLabelComponent.getModel()?.asRaDataModel()?.value = queueTitle
			subtitleLabelComponent.getModel()?.asRaDataModel()?.value = queueSubtitle
		}
		else {
			titleLabelComponent.getModel()?.asRaDataModel()?.value = ""
			subtitleLabelComponent.getModel()?.asRaDataModel()?.value = ""
			queueImageComponent.setVisible(false)
		}
	}

	fun redraw() {
		// need a full redraw if the queue is different or has been modified
		if (musicController.getQueue() != queueMetadata) {
			show()
			return
		}

		// song actually playing is different than what the current song is then update checkmark
		if (currentSong?.mediaId != musicController.getMetadata()?.mediaId) {
			val oldPlayingIndex = songsList.indexOfFirst { it.queueId == currentSong?.queueId }
			currentSong = musicController.getMetadata()
			val playingIndex = songsList.indexOfFirst { it.queueId == currentSong?.queueId }

			// remove checkmark from old song
			showList(oldPlayingIndex, 1)

			//add checkmark to new song
			showList(playingIndex, 1)
		}

		//redraw currently visible rows if one of them has a cover art that was retrieved
		for ((index, metadata) in currentVisibleRowsMusicMetadata.withIndex()) {
			if (metadata != currentlyVisibleRows[index]) {
				//can only see roughly 5 rows
				showList(max(0,currentIndex-4),8)
				break
			}
		}
	}

	/**
	 * Sets the list selection to the current song
	 */
	private fun setSelectionToCurrentSong(index: Int) {
		if (index >= 0) {
			state.app.events.values.firstOrNull { it is RHMIEvent.FocusEvent }?.triggerEvent(
					mapOf(0.toByte() to listComponent.id, 41.toByte() to index)
			)
		}
	}

	private fun onSelectAction(index: Int) {
		currentIndex = index

		if(index != 0) {
			titleLabelComponent.setVisible(false)
			subtitleLabelComponent.setVisible(false)
		} else {
			titleLabelComponent.setVisible(true)
			subtitleLabelComponent.setVisible(true)
		}
	}

	private fun showList(startIndex: Int = 0, numRows: Int = 10) {
		if(startIndex >= 0) {
			listComponent.getModel()?.setValue(songsListAdapter, startIndex, numRows, songsListAdapter.height)
		}
	}

	private fun showCurrentlyPlayingSong() {
		val selectedIndex = songsList.indexOfFirst { it.queueId == currentSong?.queueId }
		showList(selectedIndex)
		setSelectionToCurrentSong(selectedIndex)
	}

	private fun onClick(index: Int) {
		val song = songsList.getOrNull(index)
		if (song?.queueId != null) {
			musicController.playQueue(song)
		}
	}
}