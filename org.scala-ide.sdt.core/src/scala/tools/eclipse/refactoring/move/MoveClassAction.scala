package scala.tools.eclipse
package refactoring
package move

import org.eclipse.core.resources.{IFolder, IFile}
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jdt.core.IPackageFragment
import org.eclipse.jdt.internal.corext.refactoring.nls.changes.CreateFileChange
import org.eclipse.ltk.core.refactoring.resource.MoveResourceChange
import org.eclipse.ltk.core.refactoring.{RefactoringStatus, CompositeChange}

import scala.tools.eclipse.javaelements.ScalaSourceFile
import scala.tools.eclipse.refactoring.{RefactoringAction, FullProjectIndex}
import scala.tools.refactoring.analysis.GlobalIndexes
import scala.tools.refactoring.common.{TextChange, NewFileChange, ConsoleTracing}
import scala.tools.refactoring.implementations.MoveClass

/**
 * 
 */
class MoveClassAction extends RefactoringAction {
  
  def createRefactoring(start: Int, end: Int, file: ScalaSourceFile) = new MoveClassScalaIdeRefactoring(start, end, file)

  class MoveClassScalaIdeRefactoring(start: Int, end: Int, file: ScalaSourceFile) 
      extends ScalaIdeRefactoring("Move Class/Trait/Object", file, start, end) with FullProjectIndex {
      
    var moveSingleImpl: refactoring.PreparationResult = None
    
    var target: IPackageFragment = null
    
    def refactoringParameters = refactoring.RefactoringParameters(target.getElementName, moveSingleImpl)
    
    def setMoveSingleImpl(moveSingle: Boolean) {
      if(moveSingle && preparationResult.isRight) {
        // the function is never called if we don't have a value:
        moveSingleImpl = preparationResult.right.get
      } else {
        moveSingleImpl = None
      }
    }
    
    val refactoring = withCompiler { compiler =>  
      new MoveClass with GlobalIndexes with ConsoleTracing { 
        val global = compiler
        
        /* The initial index is empty, it will be filled during the initialization
         * where we can show a progress bar and let the user cancel the operation.*/
        var index = GlobalIndex(Nil)
      }
    }
    
    /**
     * A cleanup handler, will later be set by the refactoring
     * to remove all loaded compilation units from the compiler.
     */
    var cleanup = () => ()
    
    override def checkInitialConditions(pm: IProgressMonitor): RefactoringStatus = {

      val (index, cleanupHandler) = buildFullProjectIndex(pm)
      
      refactoring.index = index
      
      // will be called after the refactoring has finished
      cleanup = cleanupHandler
      
      val status = super.checkInitialConditions(pm)
      
      if(pm.isCanceled) {
        status.addWarning("Indexing was cancelled, not all references might get adapted.")
      }

      status
    }
    
    override def createChange(pm: IProgressMonitor): CompositeChange = {
      
      // the changes contain either a NewFileChange or we need to move the current file.
      
      val (textChanges, newFileChanges) = {
        (performRefactoring() :\ (List[TextChange](), List[NewFileChange]())) {
          case (change: TextChange, (textChanges, newFiles)) =>
            (change :: textChanges, newFiles)
          case (change: NewFileChange, (textChanges, newFilesChanges)) =>
            (textChanges, change :: newFilesChanges)
        }
      }
        
      val change = new CompositeChange(getName) {

        scalaChangesToEclipseChanges(textChanges) foreach add
        
        newFileChanges match {
          
          // If there's no new file to create, we obviously need to move the current file.
          case Nil => 
            add(new MoveResourceChange(file.getResource, target.getCorrespondingResource.asInstanceOf[IFolder]))

          // Otherwise, create a new file with the changes's content.
          case newFile :: rest =>
            val pth = target.getPath.append(preparationResult.right.get.get.name.toString + ".scala")
            add(new CreateFileChange(pth, newFile.text, file.getResource.asInstanceOf[IFile].getCharset)) 
        }
      }
      
      cleanup()
      
      change
    }
      
    override def getPages = {
      val selectedImpl = preparationResult.right.toOption flatMap (_.map(_.name.toString))
      List(new ui.MoveClassRefactoringConfigurationPage(file.getResource(), selectedImpl, target_=, setMoveSingleImpl))
    }
  }
}