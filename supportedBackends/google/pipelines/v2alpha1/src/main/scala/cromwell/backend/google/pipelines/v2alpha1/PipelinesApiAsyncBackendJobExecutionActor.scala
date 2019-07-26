package cromwell.backend.google.pipelines.v2alpha1

import com.google.api.services.genomics.v2alpha1.model.Mount
import com.google.cloud.storage.contrib.nio.CloudStorageOptions
import cromwell.backend.BackendJobDescriptor
import cromwell.backend.google.pipelines.common.PipelinesApiConfigurationAttributes.LocalizationConfiguration
import cromwell.backend.google.pipelines.common._
import cromwell.backend.google.pipelines.common.api.PipelinesApiRequestFactory.CreatePipelineParameters
import cromwell.backend.google.pipelines.common.io.PipelinesApiWorkingDisk
import cromwell.backend.google.pipelines.v2alpha1.PipelinesApiAsyncBackendJobExecutionActor._
import cromwell.backend.standard.StandardAsyncExecutionActorParams
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.filesystems.gcs.GcsPathBuilder.ValidFullGcsPath
import cromwell.filesystems.gcs.{GcsPath, GcsPathBuilder}
import org.apache.commons.codec.digest.DigestUtils
import wom.core.FullyQualifiedName
import wom.expression.FileEvaluation
import wom.values.{GlobFunctions, WomFile, WomGlobFile, WomMaybeListedDirectory, WomMaybePopulatedFile, WomSingleFile, WomUnlistedDirectory}

import scala.concurrent.Future
import scala.io.Source

class PipelinesApiAsyncBackendJobExecutionActor(standardParams: StandardAsyncExecutionActorParams) extends cromwell.backend.google.pipelines.common.PipelinesApiAsyncBackendJobExecutionActor(standardParams) {

  // The original implementation assumes the WomFiles are all WomMaybePopulatedFiles and wraps everything in a PipelinesApiFileInput
  // In v2 we can differentiate files from directories 
  override protected def pipelinesApiInputsFromWomFiles(inputName: String,
                                                        remotePathArray: Seq[WomFile],
                                                        localPathArray: Seq[WomFile],
                                                        jobDescriptor: BackendJobDescriptor): Iterable[PipelinesApiInput] = {
    (remotePathArray zip localPathArray) flatMap {
      case (remotePath: WomMaybeListedDirectory, localPath) =>
        maybeListedDirectoryToPipelinesParameters(inputName, remotePath, localPath.valueString)
      case (remotePath: WomUnlistedDirectory, localPath) =>
        Seq(PipelinesApiDirectoryInput(inputName, getPath(remotePath.valueString).get, DefaultPathBuilder.get(localPath.valueString), workingDisk))
      case (remotePath: WomMaybePopulatedFile, localPath) =>
        maybePopulatedFileToPipelinesParameters(inputName, remotePath, localPath.valueString)
      case (remotePath, localPath) =>
        Seq(PipelinesApiFileInput(inputName, getPath(remotePath.valueString).get, DefaultPathBuilder.get(localPath.valueString), workingDisk))
    }
  }

  // The original implementation recursively finds all non directory files, in V2 we can keep directory as is
  override protected lazy val callInputFiles: Map[FullyQualifiedName, Seq[WomFile]] = jobDescriptor.localInputs map {
    case (key, womFile) =>
      key -> womFile.collectAsSeq({
        case womFile: WomFile if !inputsToNotLocalize.contains(womFile) => womFile
      })
  }

  private lazy val transferScriptTemplate =
    Source.fromInputStream(Thread.currentThread.getContextClassLoader.getResourceAsStream("transfer.sh")).mkString

  private def localizationTransferBundle[T <: PipelinesApiInput](localizationConfiguration: LocalizationConfiguration, files: Boolean)(bucket: String, inputs: List[T]): String = {
    // `.head` is safe as there is always at least one input or this method wouldn't be invoked for the specified bucket.
    val project = inputs.head.cloudPath.asInstanceOf[GcsPath].projectId
    val maxAttempts = localizationConfiguration.localizationAttempts
    val cloudAndContainerPaths = inputs.flatMap { i => List(i.cloudPath, i.containerPath) } mkString("\"", "\"\n|  \"", "\"")

    val filesOrDirectories = if (files) "files" else "directories"

    // Use a digest as bucket names can contain characters that are not legal in bash identifiers.
    val arrayIdentifier = s"localize_${filesOrDirectories}_" + DigestUtils.md5Hex(bucket).take(7)
    s"""
       |# $bucket
       |$arrayIdentifier=(
       |  "localize" # direction
       |  "$filesOrDirectories"    # files or directories
       |  "$project"       # project
       |  "$maxAttempts"   # max attempts
       |  $cloudAndContainerPaths
       |)
       |
       |transfer "$${$arrayIdentifier[@]}"
      """.stripMargin
  }

  private def delocalizationTransferBundle[T <: PipelinesApiOutput](localizationConfiguration: LocalizationConfiguration, files: Boolean)(bucket: String, outputs: List[T]): String = {
    // `.head` is safe as there is always at least one output or this method wouldn't be invoked for the specified bucket.
    val project = outputs.head.cloudPath.asInstanceOf[GcsPath].projectId
    val maxAttempts = localizationConfiguration.localizationAttempts
    val cloudAndContainerPaths = outputs.flatMap { o => List(o.cloudPath, o.containerPath, o.contentType.getOrElse("")) } mkString("\"", "\"\n|  \"", "\"")

    val filesOrDirectories = if (files) "files" else "directories"

    // Use a digest as bucket names can contain characters that are not legal in bash identifiers.
    val arrayIdentifier = s"delocalize_${filesOrDirectories}_" + DigestUtils.md5Hex(bucket).take(7)
    s"""
       |# $bucket
       |$arrayIdentifier=(
       |  "delocalize" # direction
       |  "$filesOrDirectories"      # files or directories
       |  "$project"       # project
       |  "$maxAttempts"   # max attempts
       |  $cloudAndContainerPaths
       |)
       |
       |transfer "$${$arrayIdentifier[@]}"
      """.stripMargin
  }

  private def generateLocalizationScript(inputs: List[PipelinesApiInput], mounts: List[Mount])(implicit localizationConfiguration: LocalizationConfiguration): String = {
    // true if the collection represents files, false if directories.
    val fileInputs = (inputs collect { case i: PipelinesApiFileInput => i }, true)
    val directoryInputs = (inputs collect { case i: PipelinesApiDirectoryInput => i }, false)

    // Make transfer bundles for files and directories separately.
    val List(fileLocalizationBundles, directoryLocalizationBundles) = List(fileInputs, directoryInputs) map {
      case (is, isFile) =>
        val bundleFunction = (localizationTransferBundle(localizationConfiguration, isFile) _).tupled
        groupParametersByBucket(is).toList map bundleFunction mkString "\n"
    }

    transferScriptTemplate + fileLocalizationBundles + directoryLocalizationBundles
  }

  private def generateDelocalizationScript(outputs: List[PipelinesApiOutput], mounts: List[Mount])(implicit localizationConfiguration: LocalizationConfiguration): String = {
    // true if the collection represents files, false if directories.
    val fileOutputs = (outputs collect { case o: PipelinesApiFileOutput => o }, true)
    val directoryOutputs = (outputs collect { case o: PipelinesApiDirectoryOutput => o }, false)

    // Make transfer bundles for files and directories separately.
    val List(fileLocalizationBundles, directoryLocalizationBundles) = List(fileOutputs, directoryOutputs) map {
      case (os, isFile) =>
        val bundleFunction = (delocalizationTransferBundle(localizationConfiguration, isFile) _).tupled
        groupParametersByBucket(os).toList map bundleFunction mkString "\n"
    }

    transferScriptTemplate + fileLocalizationBundles + directoryLocalizationBundles
  }

  override def uploadLocalizationFile(createPipelineParameters: CreatePipelineParameters, cloudPath: Path, localizationConfiguration: LocalizationConfiguration): Future[Unit] = {
    val mounts = PipelinesConversions.toMounts(createPipelineParameters)
    // Only GCS inputs are currently being localized by the localization script. There will always be at least one GCS input
    // parameter in the form of the command script.
    val gcsInputs = createPipelineParameters.inputOutputParameters.fileInputParameters.filter { _.cloudPath.isInstanceOf[GcsPath] }
    val content = generateLocalizationScript(gcsInputs, mounts)(localizationConfiguration)
    asyncIo.writeAsync(cloudPath, content, Seq(CloudStorageOptions.withMimeType("text/plain")))
  }

  override def uploadDelocalizationFile(createPipelineParameters: CreatePipelineParameters, cloudPath: Path, localizationConfiguration: LocalizationConfiguration): Future[Unit] = {
    val mounts = PipelinesConversions.toMounts(createPipelineParameters)
    // Only GCS outputs are currently being localized by the localization script. There will always be GCS output
    // parameters in the form of output detritus.
    val gcsOutputs = createPipelineParameters.inputOutputParameters.fileOutputParameters.filter { _.cloudPath.isInstanceOf[GcsPath] }
    val content = generateDelocalizationScript(gcsOutputs, mounts)(localizationConfiguration)
    asyncIo.writeAsync(cloudPath, content, Seq(CloudStorageOptions.withMimeType("text/plain")))
  }

  // Simply create a PipelinesApiDirectoryOutput in v2 instead of globbing
  override protected def generateUnlistedDirectoryOutputs(unlistedDirectory: WomUnlistedDirectory, fileEvaluation: FileEvaluation): List[PipelinesApiOutput] = {
    val destination = callRootPath.resolve(unlistedDirectory.value.stripPrefix("/"))
    val (relpath, disk) = relativePathAndAttachedDisk(unlistedDirectory.value, runtimeAttributes.disks)
    val directoryOutput = PipelinesApiDirectoryOutput(makeSafeReferenceName(unlistedDirectory.value), destination, relpath, disk, fileEvaluation.optional, fileEvaluation.secondary)
    List(directoryOutput)
  }

  // De-localize the glob directory as a PipelinesApiDirectoryOutput instead of using * pattern match
  override def generateGlobFileOutputs(womFile: WomGlobFile): List[PipelinesApiOutput] = {
    val globName = GlobFunctions.globName(womFile.value)
    val globDirectory = globName + "/"
    val globListFile = globName + ".list"
    val gcsGlobDirectoryDestinationPath = callRootPath.resolve(globDirectory)
    val gcsGlobListFileDestinationPath = callRootPath.resolve(globListFile)

    val (_, globDirectoryDisk) = relativePathAndAttachedDisk(womFile.value, runtimeAttributes.disks)

    // We need both the glob directory and the glob list:
    List(
      // The glob directory:
      PipelinesApiDirectoryOutput(makeSafeReferenceName(globDirectory), gcsGlobDirectoryDestinationPath, DefaultPathBuilder.get(globDirectory), globDirectoryDisk, optional = false, secondary = false),
      // The glob list file:
      PipelinesApiFileOutput(makeSafeReferenceName(globListFile), gcsGlobListFileDestinationPath, DefaultPathBuilder.get(globListFile), globDirectoryDisk, optional = false, secondary = false)
    )
  }

  override def womFileToGcsPath(jesOutputs: Set[PipelinesApiOutput])(womFile: WomFile): WomFile = {
    womFile mapFile { path =>
      jesOutputs collectFirst {
        case jesOutput if jesOutput.name == makeSafeReferenceName(path) => jesOutput.cloudPath.pathAsString
      } getOrElse {
        GcsPathBuilder.validateGcsPath(path) match {
          case _: ValidFullGcsPath => path

          /*
            * Strip the prefixes in RuntimeOutputMapping.prefixFilters from the path, one at a time.
            * For instance
            * file:///cromwell_root/bucket/workflow_name/6d777414-5ee7-4c60-8b9e-a02ec44c398e/call-A/file.txt will progressively become
            *
            * /cromwell_root/bucket/workflow_name/6d777414-5ee7-4c60-8b9e-a02ec44c398e/call-A/file.txt
            * bucket/workflow_name/6d777414-5ee7-4c60-8b9e-a02ec44c398e/call-A/file.txt
            * call-A/file.txt
            *
            * This code is called as part of a path mapper that will be applied to the WOMified cwl.output.json.
            * The cwl.output.json when it's being read by Cromwell from the bucket still contains local paths 
            * (as they were created by the cwl tool).
            * In order to keep things working we need to map those local paths to where they were actually delocalized,
            * which is determined in cromwell.backend.google.pipelines.v2alpha1.api.Delocalization.
            */
          case _ => (callRootPath / 
            RuntimeOutputMapping
                .prefixFilters(workflowPaths.workflowRoot)
                .foldLeft(path)({
                  case (newPath, prefix) => newPath.stripPrefix(prefix)
                })
            ).pathAsString
        }
      }
    }
  }

  private def maybePopulatedFileToPipelinesParameters(inputName: String, maybePopulatedFile: WomMaybePopulatedFile, localPath: String) = {
    val secondaryFiles = maybePopulatedFile.secondaryFiles.flatMap({ secondaryFile =>
      pipelinesApiInputsFromWomFiles(secondaryFile.valueString, List(secondaryFile), List(relativeLocalizationPath(secondaryFile)), jobDescriptor)
    })

    Seq(PipelinesApiFileInput(inputName, getPath(maybePopulatedFile.valueString).get, DefaultPathBuilder.get(localPath), workingDisk)) ++ secondaryFiles
  }

  private def maybeListedDirectoryToPipelinesParameters(inputName: String, womMaybeListedDirectory: WomMaybeListedDirectory, localPath: String) = womMaybeListedDirectory match {
    // If there is a path, simply localize as a directory
    case WomMaybeListedDirectory(Some(path), _, _, _) =>
      List(PipelinesApiDirectoryInput(inputName, getPath(path).get, DefaultPathBuilder.get(localPath), workingDisk))

    // If there is a listing, recurse and call pipelinesApiInputsFromWomFiles on all the listed files
    case WomMaybeListedDirectory(_, Some(listing), _, _) if listing.nonEmpty =>
      listing.flatMap({
        case womFile: WomFile if isAdHocFile(womFile) =>
          pipelinesApiInputsFromWomFiles(makeSafeReferenceName(womFile.valueString), List(womFile), List(fileName(womFile)), jobDescriptor)
        case womFile: WomFile =>
          pipelinesApiInputsFromWomFiles(makeSafeReferenceName(womFile.valueString), List(womFile), List(relativeLocalizationPath(womFile)), jobDescriptor)
      })
    case _ => List.empty
  }

  override def generateSingleFileOutputs(womFile: WomSingleFile, fileEvaluation: FileEvaluation) = {
    val (relpath, disk) = relativePathAndAttachedDisk(womFile.value, runtimeAttributes.disks)
    // If the file is on a custom mount point, resolve it so that the full mount path will show up in the cloud path
    // For the default one (cromwell_root), the expectation is that it does not appear
    val mountedPath = if (!disk.mountPoint.isSamePathAs(PipelinesApiWorkingDisk.Default.mountPoint)) disk.mountPoint.resolve(relpath) else relpath
    // Normalize the local path (to get rid of ".." and "."). Also strip any potential leading / so that it gets appended to the call root
    val normalizedPath = mountedPath.normalize().pathAsString.stripPrefix("/")
    val destination = callRootPath.resolve(normalizedPath)
    val jesFileOutput = PipelinesApiFileOutput(makeSafeReferenceName(womFile.value), destination, relpath, disk, fileEvaluation.optional, fileEvaluation.secondary)
    List(jesFileOutput)
  }
}

object PipelinesApiAsyncBackendJobExecutionActor {
  private val gcsPathMatcher = "^gs://?([^/]+)/.*".r

  private [v2alpha1] def groupParametersByBucket[T <: PipelinesParameter](parameters: List[T]): Map[String, List[T]] = {
    parameters.foldRight(Map[String, List[T]]().withDefault(_ => List.empty)) { case (p, acc) =>
      p.cloudPath.toString match {
        case gcsPathMatcher(bucket) =>
          acc + (bucket -> (p :: acc(bucket)))
      }
    }
  }
}
