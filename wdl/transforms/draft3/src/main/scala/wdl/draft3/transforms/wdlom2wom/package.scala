package wdl.draft3.transforms

import common.transforms.CheckedAtoB

package object wdlom2wom {
  val workflowDefinitionElementToWomWorkflowDefinition = CheckedAtoB.fromErrorOr(WorkflowDefinitionElementToWomWorkflowDefinition.convert)
  val elementToWomExecutable = CheckedAtoB.fromErrorOr(FileElementToWomExecutable.convert)
}
