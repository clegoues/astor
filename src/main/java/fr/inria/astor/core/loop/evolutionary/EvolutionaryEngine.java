package fr.inria.astor.core.loop.evolutionary;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.collections.map.HashedMap;
import org.apache.log4j.Logger;

import spoon.reflect.code.CtCodeElement;
import spoon.reflect.declaration.CtSimpleType;

import com.martiansoftware.jsap.JSAPException;

import fr.inria.astor.core.entities.Gen;
import fr.inria.astor.core.entities.GenOperationInstance;
import fr.inria.astor.core.entities.GenSuspicious;
import fr.inria.astor.core.entities.ProgramVariant;
import fr.inria.astor.core.entities.ProgramVariantValidationResult;
import fr.inria.astor.core.loop.evolutionary.population.FitnessPopulationController;
import fr.inria.astor.core.loop.evolutionary.population.PopulationController;
import fr.inria.astor.core.loop.evolutionary.population.ProgramVariantFactory;
import fr.inria.astor.core.loop.evolutionary.spaces.implementation.spoon.WeightCtElement;
import fr.inria.astor.core.loop.evolutionary.spaces.ingredients.FixLocationSpace;
import fr.inria.astor.core.loop.evolutionary.spaces.operators.RepairOperatorSpace;
import fr.inria.astor.core.manipulation.MutationSupporter;
import fr.inria.astor.core.manipulation.bytecode.entities.CompilationResult;
import fr.inria.astor.core.setup.ConfigurationProperties;
import fr.inria.astor.core.setup.ProjectRepairFacade;
import fr.inria.astor.core.stats.StatPatch;
import fr.inria.astor.core.stats.Stats;
import fr.inria.astor.core.util.StringUtil;
import fr.inria.astor.core.util.TimeUtil;
import fr.inria.astor.core.validation.validators.ProgramValidator;

/**
 * Evolutionary program transformation Loop
 * 
 * @author Matias Martinez, matias.martinez@inria.fr
 * 
 */
public abstract class EvolutionaryEngine {

	/**
	 * Initial identifier.
	 */
	public static int firstgenerationIndex = 1;

	/**
	 * Statistic
	 */
	protected Stats currentStat = new Stats();

	protected Logger log = Logger.getLogger(Thread.currentThread().getName());

	protected ProgramVariantFactory variantFactory;

	protected ProgramValidator programValidator;

	// INTERNAL
	protected List<ProgramVariant> variants = new ArrayList<ProgramVariant>();
	protected List<ProgramVariant> solutions = new ArrayList<ProgramVariant>();

	public List<ProgramVariant> getSolutions() {
		return solutions;
	}

	protected ProgramVariant originalVariant = null;

	// SPACES
	protected FixLocationSpace<String, CtCodeElement, String> fixspace = null;

	protected RepairOperatorSpace repairActionSpace = null;

	protected PopulationController populationControler = null;

	// CODE MANAGMENT
	protected MutationSupporter mutatorSupporter = null;

	protected ProjectRepairFacade projectFacade = null;

	/**
	 * 
	 * @param mutatorExecutor
	 * @throws JSAPException
	 */
	public EvolutionaryEngine(MutationSupporter mutatorExecutor, ProjectRepairFacade projFacade) throws JSAPException {
		this.mutatorSupporter = mutatorExecutor;
		this.projectFacade = projFacade;
	}

	/**
	 * 
	 * @throws Exception
	 */
	public void startEvolution() throws Exception {

		log.info("\n----Starting Mutation" );
		long startT = System.currentTimeMillis();
	
		
		int generation = 0;
		boolean stop = false;

		log.debug("FIXSPACE:" + this.getFixSpace());

		currentStat.passFailingval1 = 0;
		currentStat.passFailingval2 = 0;
		
		
		
		Date dateInit = new Date();

		int maxMinutes = ConfigurationProperties.getPropertyInt("maxtime");

		while ( !this.variants.isEmpty() &&
				(!stop || !ConfigurationProperties.getPropertyBool("stopfirst"))
				&& (generation < ConfigurationProperties.getPropertyInt("maxGeneration") && continueOperating(dateInit,
						maxMinutes))) {
			generation++;
			log.info("\n----------Running generation/iteraction " + generation + ", population size: "
					+ this.variants.size());
			stop = processGenerations(generation);
		}
		// At the end

		if (!this.solutions.isEmpty()) {
			log.info("End Repair Loops: Found solution");
			log.info("Solution stored at: " + projectFacade.getProperties().getInDir());

		} else {
			log.info("End Repair Loops: NOT Found solution");
		}
		log.info("Number solutions:" + this.solutions.size());
		for (ProgramVariant variant : solutions) {
			log.info("f (sol): " + variant.getFitness() + ", " + variant);
		}
		log.info("All variants:");
		for (ProgramVariant variant : variants) {
			log.info("f " + variant.getFitness() + ", " + variant);
		}

		if (!solutions.isEmpty()) {
			log.info("\nSolution details");
			log.info(mutatorSupporter.getSolutionData(solutions, generation));

		}
		

		long endT = System.currentTimeMillis();
		log.info("Time Evolution(ms): " + (endT - startT));
		currentStat.timeIteraction = ((endT - startT));
		
		
		log.info("\n----stats: ");
		log.info(currentStat);
		
	}

	/**
	 * Check whether the program has passed the maximum time for operating
	 * 
	 * @param dateInit
	 *            start date of execution
	 * @param maxMinutes
	 *            max minutes for operating
	 * @return
	 */
	private boolean continueOperating(Date dateInit, int maxMinutes) {
		if (TimeUtil.delta(dateInit) <= maxMinutes) {
			return true;
		} else {
			log.info("\n No more time for operating");
			return false;
		}
	}

	/**
	 * Process a generation i: loops over all instances
	 * 
	 * @param generation
	 * @return
	 * @throws Exception
	 */
	public boolean processGenerations(int generation) throws Exception {

		log.info("\n***** Generation " + generation);
		boolean foundSolution = false;

		List<ProgramVariant> temporalInstances = new ArrayList<ProgramVariant>();

		currentStat.numberGenerations++;
		
		for (ProgramVariant parentVariant : variants) {

						
			log.debug("\n**\n*-Parent Variant: " + parentVariant);

			this.saveOriginalVariant(parentVariant);
			ProgramVariant newVariant = createNewProgramVariant(parentVariant, generation);
			this.saveModifVariant(parentVariant);

			if (newVariant == null) {
				continue;
			}

			boolean solution = processCreatedVariant(newVariant, generation);

			//if (newVariant.getCompilation().compiles()) {
				temporalInstances.add(newVariant);
			//}
				
			if (solution) {
				foundSolution = true;
			}

			// Finally, reverse the changes done by the child
			reverseOperationInModel(newVariant, generation);
			this.validateReversedOriginalVariant(newVariant);
			if (foundSolution && ConfigurationProperties.getPropertyBool("stopfirst")) {
				break;
			}

		}
		prepareNextGeneration(temporalInstances, generation);

		return foundSolution;
	}

	public void prepareNextGeneration(List<ProgramVariant> temporalInstances, int generation) {
		// After analyze all variant
		// New population creation:
		//show all and search solutions:
		List<ProgramVariant> solutionsFromGeneration = new ArrayList<ProgramVariant>();
		List<ProgramVariant> currentVariants = new ArrayList<>();

		//We filter the solution from the rest
		String solutionId = "";
		for (ProgramVariant programVariant : temporalInstances) {
			if (programVariant.isSolution()) {
				solutionsFromGeneration.add(programVariant);
				solutionId += programVariant.getId() + "(SOLUTION)(f=" + programVariant.getFitness() + ")" + ", ";
			}else{
				currentVariants.add(programVariant);
			}
		}
		log.debug("\nEnd analysis generation - \nSolutions found:" + "--> (" + solutionId + ")");

		this.solutions.addAll(solutionsFromGeneration);
		
		//We add the case we do not have variants because all are solution, be we want continue searching new ones
		if(currentVariants.isEmpty() && !solutionsFromGeneration.isEmpty() && !ConfigurationProperties.getPropertyBool("stopfirst")){
			currentVariants.addAll(solutionsFromGeneration);
		} 
		
		variants = populationControler.selectProgramVariantsForNextGeneration(variants, currentVariants,
				 ConfigurationProperties.getPropertyInt("population"));

		if (ConfigurationProperties.getProperty("reintroduce").contains("original")){
			// Create a new variant from the original parent
			ProgramVariant parentNew = this.variantFactory.createProgramVariantFromAnother(originalVariant, generation);
			parentNew.getOperations().clear();
			parentNew.setParent(null);
			if (!variants.isEmpty()) {
				// now replace for the "worse" child
				variants.remove(variants.size() - 1);

			}
			variants.add(parentNew);
			// log.debug("Introducing original variant"+((removedVariant!=null)?"instead of variant "+removedVariant.getId():""));

		}

	}

	Map<String, String> originalModel = new HashedMap();
	Map<String, String> modifModel = new HashedMap();

	private void saveOriginalVariant(ProgramVariant variant) {
		originalModel.clear();
		for (CtSimpleType st : variant.getAffectedClasses()) {
			originalModel.put(st.getQualifiedName(), st.toString());
		}

	}

	private void saveModifVariant(ProgramVariant variant) {
		modifModel.clear();
		for (CtSimpleType st : variant.getAffectedClasses()) {
			modifModel.put(st.getQualifiedName(), st.toString());
		}

	}

	private void validateReversedOriginalVariant(ProgramVariant variant) {

		for (CtSimpleType st : variant.getAffectedClasses()) {
			String original = originalModel.get(st.getQualifiedName());
			boolean idem = original.equals(st.toString());
			if (!idem) {
				log.error("Error: the model was not the same from the original after this generation");
				// throw new
				// IllegalStateException("the model was not the same from the original after this generation");
			}
		}

	}

	/**
	 * 
	 * Compiles and validates a created variant.
	 * 
	 * @param parentVariant
	 * @param generation
	 * @return true if the variant is a solution. False otherwise.
	 * @throws Exception
	 */
	public boolean processCreatedVariant(ProgramVariant programVariant, int generation) throws Exception {

		URL[] originalURL = projectFacade.getURLforMutation(ProgramVariant.DEFAULT_ORIGINAL_VARIANT);

		CompilationResult compilation = mutatorSupporter.compileOnMemoryProgramVariant(programVariant, originalURL);

		boolean childCompiles = compilation.compiles();
		programVariant.setCompilation(compilation);

		String srcOutput = projectFacade.getInDirWithPrefix(programVariant.currentMutatorIdentifier());

		if (ConfigurationProperties.getPropertyBool("saveall")) {
			log.debug("\n-Saving child on disk variant #" + programVariant.getId() + " at " + srcOutput);
			// This method should be refactored, and replace by the
			// output from memory compilation
			mutatorSupporter.saveSourceCodeOnDiskProgramVariant(programVariant, srcOutput);
		}

		if (childCompiles) {

			log.debug("-The child compiles: id " + programVariant.getId());
			currentStat.numberOfRightCompilation++;

			boolean validInstance = validateInstance(programVariant);
			log.debug("-Valid?: " + validInstance + ", fitness " + programVariant.getFitness());
			if (validInstance) {
				log.info("-Found Solution, child variant #" + programVariant.getId());
				saveStaticSucessful(generation);
				if (ConfigurationProperties.getPropertyBool("savesolution")) {
					mutatorSupporter.saveSourceCodeOnDiskProgramVariant(programVariant, srcOutput);
					mutatorSupporter.saveSolutionData(programVariant, srcOutput, generation);
				}
				return true;
			}
		} else {
			log.debug("-The child does NOT compile: " + programVariant.getId() + ", errors: " + "");
			currentStat.numberOfFailingCompilation++;
			programVariant.setFitness(this.populationControler.getMaxFitnessValue());

		}
		return false;

	}

	protected void saveStaticSucessful(int generation) {
		currentStat.patches++;
		currentStat.genPatches.add(new StatPatch(generation, currentStat.passFailingval1, currentStat.passFailingval2));
		// log.debug("-->" + currentStat.passFailingval1 + " - " +
		// currentStat.passFailingval2);
		currentStat.passFailingval1 = 0;
		currentStat.passFailingval2 = 0;
	}

	/**
	 * Create a child mutated. Return null if not mutation is produced by the
	 * engine (i.e. the child is equal to the parent)
	 * 
	 * @param parentVariant
	 * @param generation
	 * @param idsChild
	 * @return
	 * @throws Exception
	 */
	protected ProgramVariant createNewProgramVariant(ProgramVariant parentVariant, int generation) throws Exception {
		// This is the copy of the original program
		ProgramVariant childVariant = variantFactory.createProgramVariantFromAnother(parentVariant, generation);
		log.debug("\n--Child created id: " + childVariant.getId());

		// Apply previous operations (i.e., from previous operators)
		applyPreviousOperationsToVariantModel(childVariant, generation);

		boolean isChildMutatedInThisGeneration = modifyProgramVariant(childVariant, generation);

		if (!isChildMutatedInThisGeneration) {
			log.debug("--Not Operation generated in child variant: " + childVariant);
			reverseOperationInModel(childVariant, generation);
			return null;
		}

		boolean appliedOperations = applyNewOperationsToVariantModel(childVariant, generation);

		if (!appliedOperations) {
			log.debug("---Not Operation applied in child variant:" + childVariant);
			reverseOperationInModel(childVariant, generation);
			return null;
		}

		return childVariant;
	}

	/**
	 * Undo in reverse order that the mutation were applied.
	 * 
	 * @param variant
	 * @param generation
	 */
	protected void reverseOperationInModel(ProgramVariant variant, int generation) {

		if (variant.getOperations() == null || variant.getOperations().isEmpty()) {
			return;
		}
		// For each generation, in reverse order they are generated.

		for (int genI = generation; genI >= 1; genI--) {

			undoSingleGeneration(variant, genI);
		}
	}

	protected void undoSingleGeneration(ProgramVariant instance, int genI) {
		List<GenOperationInstance> operations = instance.getOperations().get(genI);
		if (operations == null || operations.isEmpty()) {
			return;
		}
		// log.debug("--Undoing #operations: " + operations.size()
		// + " of generation " + genI);

		for (int i = operations.size() - 1; i >= 0; i--) {
			GenOperationInstance genOperation = operations.get(i);
			log.debug("---Undoing: gnrtn(" + genI + "): " + genOperation);
			undoOperationToSpoonElement(genOperation);
		}
	}

	/**
	 * For each gen of the variant, create a mutation. These are stored in the
	 * program variant.
	 * 
	 * @param variant
	 * @param generation
	 * @return if at least one gen mutation is created
	 * @throws Exception
	 */
	Random random = new Random();

	/**
	 * Given a program variant, the method generates operations for modifying
	 * that variants. Each operation is related to one gen of the program
	 * variant.
	 * 
	 * @param variant
	 * @param generation
	 * @return
	 * @throws Exception
	 */
	private boolean modifyProgramVariant(ProgramVariant variant, int generation) throws Exception {

		log.debug("--Creating new operations for variant " + variant);
		boolean oneOperationCreated = false;
		int genMutated = 0, notmut = 0, notapplied = 0;
		int nroGen = 0;

		// For each gen of the program instance
		List<Gen> gensToProcess = getGenList(variant);
		for (Gen genProgInstance : gensToProcess) {
			//tp refactor
			genProgInstance.identified = variant.getGenList().indexOf(genProgInstance);
			log.debug("---analyzing gen position: "+genProgInstance.identified );
			
			//A gen can be modified several time in the evolution
			boolean multiGenmutation = ConfigurationProperties.getPropertyBool("multigenmodif");
			if (!multiGenmutation && alreadyModified(genProgInstance, variant.getOperations(), generation))
				continue;

			genProgInstance.setProgramVariant(variant);
			GenOperationInstance operationInGen = createOperationForGen(genProgInstance);
		
			if (operationInGen != null) {

				operationInGen.setGen(genProgInstance);
				
				if(ConfigurationProperties.getPropertyBool("uniqueoptogen") 
						&& alreadyApplied(operationInGen)){
					log.debug("---Operation already applied to the gen "+operationInGen);
					continue;
				}
				
				
				// TODO: Verifies if there are compatible variables (not names!)
				// if (VariableResolver.canBeApplied(operationInGen))
				log.debug("operation "+operationInGen);
				currentStat.numberOfAppliedOp++;
				variant.putGenOperation(generation, operationInGen);
				
				oneOperationCreated = true;
				genMutated++;
				//We analyze all gens
				if (!ConfigurationProperties.getPropertyBool("allgens")) {
					break;
				}

			} else {// Not gen created
				currentStat.numberOfGenInmutated++;
				log.debug("---gen " + (nroGen++) + " not mutation generated in  "
						+ StringUtil.trunc(genProgInstance.getRootElement().getSignature()));
				notmut++;
			}
		}
		
		if (oneOperationCreated && !ConfigurationProperties.getPropertyBool("resetoperations")) {
			updateVariantGenList(variant, generation);
		}
		log.debug("\n--Summary Creation: for variant " + variant + " gen mutated: " + genMutated + " , gen not mut: " + notmut
				+ ", gen not applied  " + notapplied + "\n ");
		return oneOperationCreated;
	}

	Map<Gen,List<GenOperationInstance>> operationGenerated = new HashedMap();
	
	private boolean alreadyApplied(GenOperationInstance operationNew) {
		
		List<GenOperationInstance> ops = operationGenerated.get(operationNew.getGen());
		if(ops == null){
			ops = new ArrayList<>();
			operationGenerated.put(operationNew.getGen(), ops);
			ops.add(operationNew);
			return false;
		}
		return ops.contains(operationNew);
	}

	/**
	 * Return true if the gen passed as parameter was already affected by a
	 * previous operator.
	 * 
	 * @param genProgInstance
	 * @param map
	 * @param generation
	 * @return
	 */
	private boolean alreadyModified(Gen genProgInstance, Map<Integer, List<GenOperationInstance>> map, int generation) {

		for (int i = 1; i < generation; i++) {
			List<GenOperationInstance> ops = map.get(i);
			if (ops == null)
				continue;
			for (GenOperationInstance genOperationInstance : ops) {
				if (genOperationInstance.getGen() == genProgInstance) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 
	 * @param variant 
	 * @param genList
	 * @return
	 */
	protected List<Gen> getGenList(ProgramVariant variant) {
		List<Gen> genList = variant.getGenList();
		String mode = ConfigurationProperties.getProperty("genlistnavigation");

		if ("inorder".equals(mode))
			return genList;

		if ("weight".equals(mode))
			return getWeightGenList(genList);

		if ("random".equals(mode)) {
			List<Gen> shuffList = new ArrayList<Gen>(genList);
			Collections.shuffle(shuffList);
			return shuffList;
		}
		
		if("sequence".equals(mode)){
			int i = variant.getLastGenAnalyzed();
			if(i< genList.size()){
				variant.setLastGenAnalyzed(i+1);	
				return genList.subList(i,i+1);
			}
			return Collections.EMPTY_LIST;
		}

		return genList;

	}

	protected List<Gen> getWeightGenList(List<Gen> genList) {
		List<Gen> remaining = new ArrayList<Gen>(genList);
		List<Gen> solution = new ArrayList<Gen>();

		for (int i = 0; i < genList.size(); i++) {
			List<WeightCtElement> we = new ArrayList<WeightCtElement>();
			double sum = 0;
			for (Gen gen : remaining) {
				double susp = ((GenSuspicious) gen).getSuspicious().getSuspiciousValue();
				sum += susp;
				WeightCtElement w = new WeightCtElement(gen, 0);
				w.weight = susp;
				we.add(w);
			}

			for (WeightCtElement weightCtElement : we) {
				weightCtElement.weight = weightCtElement.weight / sum;
			}

			WeightCtElement.feedAccumulative(we);
			WeightCtElement selected = WeightCtElement.selectElementWeightBalanced(we);

			Gen selectedg = (Gen) selected.element;
			remaining.remove(selectedg);
			solution.add(selectedg);
		}
		return solution;

	}

	private void updateVariantGenList(ProgramVariant variant, int generation) {
		List<GenOperationInstance> operations = variant.getOperations().get(generation);
		// log.debug("--Updating Model from Program Variants ");

		for (GenOperationInstance genOperationInstance : operations) {
			updateVariantGenList(variant, genOperationInstance);
		}
	}

	/**
	 * This method updates gens of a variant according to a created
	 * GenOperationInstance
	 * 
	 * @param variant
	 * @param operationofGen
	 */
	protected abstract void updateVariantGenList(ProgramVariant variant, GenOperationInstance operation);

	/**
	 * Create a Gen Mutation for a given CtElement
	 * 
	 * @param ctElementPointed
	 * @param className
	 * @param suspValue
	 * @return
	 * @throws IllegalAccessException
	 */
	protected abstract GenOperationInstance createOperationForGen(Gen genProgInstance) throws IllegalAccessException;

	protected abstract void undoOperationToSpoonElement(GenOperationInstance operation);

	/**
	 * Apply a mutation generated in previous generation to a model
	 * 
	 * @param variant
	 * @param currentGeneration
	 * @throws IllegalAccessException
	 */
	protected void applyPreviousOperationsToVariantModel(ProgramVariant variant, int currentGeneration)
			throws IllegalAccessException {

		// We do not include the current generation (should be empty)
		for (int generation_i = firstgenerationIndex; generation_i < currentGeneration; generation_i++) {

			List<GenOperationInstance> operations = variant.getOperations().get(generation_i);
			if (operations == null || operations.isEmpty()) {
				continue;
			}
			for (GenOperationInstance genOperation : operations) {
				applyPreviousMutationOperationToSpoonElement(genOperation);
				log.debug("----gener( " + generation_i + ") `" + genOperation.isSuccessfulyApplied() + "`, "
						+ genOperation.toString());

			}

		}
	}

	/**
	 * Apply the mutation generated in the current Generation
	 * 
	 * @param variant
	 * @param currentGeneration
	 * @throws IllegalAccessException
	 */
	public boolean applyNewOperationsToVariantModel(ProgramVariant variant, int currentGeneration)
			throws IllegalAccessException {
		// New: for the operation of each generation

		// log.debug("---Apply New mutations to variant: " + variant);

		List<GenOperationInstance> operations = variant.getOperations().get(currentGeneration);
		if (operations == null || operations.isEmpty()) {
			return false;
		}

		for (GenOperationInstance genOperation : operations) {

			applyNewMutationOperationToSpoonElement(genOperation);
			// log.debug("----gener(" + currentGeneration+ ")" + genOperation);

		}

		// For the last generation,remove operation with exceptions
		// Clean Operations not applied:
		int size = operations.size();
		for (int i = 0; i < size; i++) {
			GenOperationInstance genOperationInstance = operations.get(i);
			if (genOperationInstance.getExceptionAtApplied() != null || !genOperationInstance.isSuccessfulyApplied()) {
				log.debug("---Error! Deleting " + genOperationInstance + " failed by a "
						+ genOperationInstance.getExceptionAtApplied());
				operations.remove(i);
				i--;
				size--;
			}
		}
		return !(operations.isEmpty());
	}

	protected abstract void applyPreviousMutationOperationToSpoonElement(GenOperationInstance operation)
			throws IllegalAccessException;

	/**
	 * Apply a given Mutation to the node referenced by the operation
	 * 
	 * @param operation
	 * @throws IllegalAccessException
	 */
	protected abstract void applyNewMutationOperationToSpoonElement(GenOperationInstance operation)
			throws IllegalAccessException;

	protected boolean validateInstance(ProgramVariant variant) {
		ProgramVariantValidationResult result;
		
		if ((result = programValidator.validate(variant, projectFacade)) != null) {
			double fitness = this.populationControler.getFitnessValue(variant, result);
			variant.setFitness(fitness);
			boolean wasSuc = result.wasSuccessful();
			variant.setIsSolution(wasSuc);
			return wasSuc;
		}
		return false;
	}

	public RepairOperatorSpace getRepairActionSpace() {
		return repairActionSpace;
	}

	public void setRepairActionSpace(RepairOperatorSpace repairSpace) {
		this.repairActionSpace = repairSpace;
	}

	public List<ProgramVariant> getVariants() {
		return variants;
	}

	public FixLocationSpace getFixSpace() {
		return fixspace;
	}

	public void setFixspace(FixLocationSpace fixspace) {
		this.fixspace = fixspace;
	}

	public ProgramVariantFactory getVariantFactory() {
		return variantFactory;
	}

	public MutationSupporter getMutatorSupporter() {
		return mutatorSupporter;
	}

	public void setMutatorSupporter(MutationSupporter mutatorSupporter) {
		this.mutatorSupporter = mutatorSupporter;
	}

	public PopulationController getPopulationControler() {
		return populationControler;
	}

	public void setPopulationControler(PopulationController populationControler) {
		this.populationControler = populationControler;
	}

	public void setProjectFacade(ProjectRepairFacade projectFacade) {
		this.projectFacade = projectFacade;
	}

	public void setVariantFactory(ProgramVariantFactory variantFactory) {
		this.variantFactory = variantFactory;
	}

	public ProgramValidator getProgramValidator() {
		return programValidator;
	}

	public void setProgramValidator(ProgramValidator programValidator) {
		this.programValidator = programValidator;
		this.programValidator.setStats(currentStat);
	}

	public Stats getCurrentStat() {
		return currentStat;
	}

	public void setCurrentStat(Stats currentStat) {
		this.currentStat = currentStat;
	}

}
