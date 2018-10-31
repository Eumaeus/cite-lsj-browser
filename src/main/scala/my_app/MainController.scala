package citeLexicon 
import com.thoughtworks.binding.{Binding, dom}
import com.thoughtworks.binding.Binding.{BindingSeq, Var, Vars}
import scala.scalajs.js
import scala.scalajs.js._
import js.annotation._
import scala.concurrent._
//import ExecutionContext.Implicits.global
import collection.mutable
import collection.mutable._
import scala.scalajs.js.Dynamic.{ global => g }
import org.scalajs.dom._
import org.scalajs.dom.ext._
import org.scalajs.dom.raw._
import edu.holycross.shot.cite._
import edu.holycross.shot.scm._
import edu.holycross.shot.ohco2._
import edu.holycross.shot.citeobj._
import edu.holycross.shot.citejson._

import edu.holycross.shot.greek._

import monix.execution.Scheduler.Implicits.global
import monix.eval._

import scala.scalajs.js.annotation.JSExport

@JSExportTopLevel("citeLexicon.MainController")
object MainController {


	/* 
		Initiate app 
	*/
	@JSExport
	def main(defaultServiceUrl:String): Unit = {
		dom.render(document.body, MainView.mainDiv)
		MainModel.serviceUrl.value = defaultServiceUrl
		g.console.log(s"using: ${MainModel.serviceUrl.value}")
		// begin getting index	
		updateUserMessage("Loading index to lexicon…",1)
		loadIndex(MainModel.indexFile)
	}

	/*
	 	Handles displaying messages to the user, color-coded according to type.
	 	Fades after a period of time, defined in the setTimeout().
	*/
	def updateUserMessage(msg: String, alert: Int): Unit = {
		MainModel.userMessageVisibility.value = "app_visible"
		MainModel.userMessage.value = msg
		alert match {
			case 0 => MainModel.userAlert.value = "default"
			case 1 => MainModel.userAlert.value = "wait"
			case 2 => MainModel.userAlert.value = "warn"
		}
		js.timers.clearTimeout(MainModel.msgTimer)
		MainModel.msgTimer = js.timers.setTimeout(10000){ MainModel.userMessageVisibility.value = "app_hidden" }
	}

	/* Validates URN */
	def validatePassage(urnString:String):Boolean = {
		try {
			val u:Cite2Urn = Cite2Urn(urnString)
			if (u.toString.contains("urn:cite2:hmt:lsj.markdown:")) {
				if (u.objectComponentOption == None) false else true
			} else {
				false			
			}
		} catch {
			case e:Exception => false
		}
	}

	/*
		Use AJAX request to get remote data
		`callback` is the name of a function that should take a single parameter of type String
	*/
	def getJson(callback: (String, Option[Urn]) => Unit, query:String, url: String = MainModel.serviceUrl.value, urn:Option[Urn] = None):Unit = {

		val xhr = new XMLHttpRequest()
		xhr.open("GET", s"${url}${query}" )
		xhr.onload = { (e: Event) =>
			if (xhr.status == 200) {
				val contents:String = xhr.responseText
				callback(contents, urn)
			} else {
				MainController.updateUserMessage(s"Request for info on remote library failed with code ${xhr.status}",2)
			}
		}
		xhr.send()
	}

		/*
		Use AJAX request to get lexicon index; load it
	*/
	def loadIndex(url: String):Unit = {

		g.console.log(s"doing request with ${url}")	
		val xhr = new XMLHttpRequest()
		xhr.open("GET", url )
		xhr.onload = { (e: Event) =>
			if (xhr.status == 200) {
				val contents:String = xhr.responseText
				MainModel.updateIndex(contents)
			} else {
				MainController.updateUserMessage(s"Request for index failed with code ${xhr.status}",2)
			}
		}
		xhr.send()
	}

	def greekify(s:String):String = {
		if (s.size > 0){
			val gs:LiteraryGreekString = LiteraryGreekString(s)
			val ugs:String = ucodePlus(gs)
			ugs
		} else {
			""
		}
	}

	def ucodePlus(s:LiteraryGreekString):String = {
		val sigmaTerminators:Vector[String] = Vector(",",".",":", ";", "'", "—", " ", "\t")
		val punctuationMatcher = "[.,:;]".r
		val uc1:String = s.ucode.replaceAll(":","·")
		val uc2:String = {
			if (uc1.last == 'σ') {
				s"ς${uc1.reverse.tail}".reverse
			} else { uc1 }
		}
		val matcher = "σ.".r	
		val uc3 = {
			matcher.replaceAllIn(uc2, m => {
				val secondChar:String = m.group(0).tail
				if (sigmaTerminators.contains(secondChar)) { s"ς${secondChar}"}
				else { s"σ${secondChar}"}
			})
		}

		uc3

	}

	def queryIndex(s:String):Unit = {
		MainModel.mainIndex.value match {
			case None => // do nothing
				case Some(idx) => {
					val perfectMatches:Vector[MainModel.LexIndex] = {
						idx.filter(_.betacodeKey.trim.equals(s))
					}
					val startsWithMatches:Vector[MainModel.LexIndex] = {
						idx.filter(_.betacodeKey.trim.startsWith(s))
					}
					val foundKeys:Vector[MainModel.LexIndex] = {
						idx.filter(_.betacodeKey.contains(s))
					}
					val foundInEntry:Vector[MainModel.LexIndex] = {
						idx.filter(_.terms.contains(s))
					}
					val foundStuff:Vector[MainModel.LexIndex] = {
						(perfectMatches ++ startsWithMatches ++ foundKeys ++ foundInEntry).distinct
						//perfectMatches
					}
					MainModel.clearSidebar
					MainModel.currentResults.value.clear
					for (i <- foundStuff) {
						MainModel.currentResults.value += i
					}
				}
		}
	}

	def initLsjSingleQuery(idString:String):Unit = {

		// Init query to get that going

		val lsjUrn:Cite2Urn = MainModel.lexiconUrn.addSelector(idString)
		val queryString:String = s"objects/${lsjUrn}"

		val task = Task{ MainController.getJson(callback = MainController.processLsj, query = queryString, url = MainModel.serviceUrl.value, urn = Some(lsjUrn)) }
		val future = task.runAsync

		// Deal with UI while we wait
		MainModel.selectedInShownIndex.value = Some(idString)
		// sideBar
		MainModel.mainIndex.value match {
			case None => // do nothing
			case Some(mi) => {
				val sideBarId:String = s"entry_${idString}"
				val thisIndexEntry:Option[MainModel.LexIndex] = {
					val matchedVec = mi.filter(_.selector == idString).toVector
					if (matchedVec.size < 1) None else Some(matchedVec(0))
				}
				thisIndexEntry match {
					case None => // do nothing
					case Some(i) => {
						val letter:Char = i.betacodeKey.toVector(0)
						val alphaVolume:Option[MainModel.AlphaVolume] = {
							val alphaVec:Vector[MainModel.AlphaVolume] = MainModel.alphaIndex.value.filter( _.beta.toVector(0).toString == letter.toString).toVector
							if (alphaVec.size > 0) Some(alphaVec(0)) else None
						}

						alphaVolume match {
							case Some(av) => {
								MainModel.activeVolume.value = Some(av)
								MainModel.setActiveAlpha(av.key)
								// Scroll to it
								MainView.scrollToItemInSidebar(sideBarId)
							}
							case _ => // do nothing
						}
					}
				}

			}
		}


	}


	def processLsj(jstring:String, urn:Option[Urn] = None):Unit = {
		val objJson:CiteObjJson = CiteObjJson()
		val vco:Vector[CiteObject] = objJson.vectorOfCiteObjects(jstring)
		MainModel.updateLexEntries(vco)	
	}



}