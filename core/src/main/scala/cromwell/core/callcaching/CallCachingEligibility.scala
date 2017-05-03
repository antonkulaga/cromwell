package cromwell.core.callcaching

sealed trait CallCachingEligibility
sealed trait CallCachingEligible extends CallCachingEligibility
sealed trait CallCachingIneligible extends CallCachingEligibility {
  def message: String
}
  
case object NoDocker extends CallCachingEligible
case class DockerWithHash(dockerAttribute: String) extends CallCachingEligible
case class FloatingDockerTagWithHash(dockerAttributeWithTag: String, dockerAttributeWithHash: String) extends CallCachingEligible

case object FloatingDockerTagWithoutHash extends CallCachingIneligible {
  override val message = s"""You are using a floating docker tag in this task. Cromwell does not consider tasks with floating tags to be eligible for call caching.
         |If you want this task to be eligible for call caching in the future, use a docker runtime attribute with a digest instead.
         |Cromwell attempted to retrieve the current hash for this docker image but failed.
         |This is not necessarily a cause for concern as Cromwell is currently only able to retrieve hashes for Dockerhub and GCR images.
         |The job will be dispatched to the appropriate backend that will attempt to run it.""".stripMargin
}
