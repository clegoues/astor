package fr.inria.main.evolution;

import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.ParseException;

import fr.inria.astor.core.entities.ProgramVariant;
import fr.inria.astor.core.faultlocalization.entity.SuspiciousCode;
import fr.inria.astor.core.loop.evolutionary.JGenProg;
import fr.inria.astor.core.loop.evolutionary.population.FitnessPopulationController;
import fr.inria.astor.core.loop.evolutionary.population.ProgramVariantFactory;
import fr.inria.astor.core.loop.evolutionary.spaces.implementation.RemoveRepairOperatorSpace;
import fr.inria.astor.core.loop.evolutionary.spaces.implementation.UniformRandomRepairOperatorSpace;
import fr.inria.astor.core.loop.evolutionary.spaces.implementation.spoon.processor.AbstractFixSpaceProcessor;
import fr.inria.astor.core.loop.evolutionary.spaces.implementation.spoon.processor.SingleStatementFixSpaceProcessor;
import fr.inria.astor.core.loop.evolutionary.spaces.ingredients.BasicFixSpace;
import fr.inria.astor.core.manipulation.MutationSupporter;
import fr.inria.astor.core.setup.ConfigurationProperties;
import fr.inria.astor.core.validation.validators.ProcessValidator;
import fr.inria.main.AbstractMain;

/**
 * Main for full version of jGenProg
 * 
 * @author Matias Martinez, matias.martinez@inria.fr
 *
 */
public class MainjGenProg extends AbstractMain {

	
	public void initProject(String location, String projectName, String dependencies, String packageToInstrument,
			double thfl, String failing) throws Exception {
			
		List<String> failingList = Arrays.asList(failing.split(File.pathSeparator));
		String method = this.getClass().getSimpleName();
		projectFacade = getProject(location, projectName, method, failingList, dependencies, true);
		projectFacade.getProperties().setExperimentName(this.getClass().getSimpleName());

		projectFacade.setupTempDirectories(ProgramVariant.DEFAULT_ORIGINAL_VARIANT);
		
		projectFacade.calculateRegression(ProgramVariant.DEFAULT_ORIGINAL_VARIANT);
		
	}
	public JGenProg statementMode() throws Exception {
		return statementMode(false);
	}
	
	/**
	 * 
	 * @param removeMode
	 * @return
	 * @throws Exception
	 */
	public JGenProg statementMode(boolean removeMode) throws Exception {

		MutationSupporter mutSupporter = new MutationSupporter(getFactory());
		JGenProg gploop = new JGenProg(mutSupporter, projectFacade);

		// Fix Space
		List<AbstractFixSpaceProcessor<?>> ingredientProcessors = new ArrayList<AbstractFixSpaceProcessor<?>>();
		ingredientProcessors.add(new SingleStatementFixSpaceProcessor());
		// ingredientProcessors.add(new LoopExpressionFixSpaceProcessor());
		// ingredientProcessors.add(new IFExpressionFixSpaceProcessor());
		// ingredientProcessors.add(new MethodInvocationFixSpaceProcessor());

		// We analyze
		gploop.setVariantFactory(new ProgramVariantFactory(ingredientProcessors));
		// --

		// The ingredients for build the patches
		gploop.setFixspace(new BasicFixSpace(ingredientProcessors));
		// ---

		// Repair Space
		if(removeMode){
			gploop.setRepairActionSpace(new RemoveRepairOperatorSpace());
			ConfigurationProperties.properties.setProperty("resetoperations", "true");
			ConfigurationProperties.properties.setProperty("reintroduce","none");	
			ConfigurationProperties.properties.setProperty("genlistnavigation", "sequence");
			ConfigurationProperties.properties.setProperty("stopfirst", "false");
			ConfigurationProperties.properties.setProperty("regressionforfaultlocalization","true");
			ConfigurationProperties.properties.setProperty("population","1");
		}
		else{
			gploop.setRepairActionSpace(new UniformRandomRepairOperatorSpace());
		}
		
		// Pop controller
		gploop.setPopulationControler(new FitnessPopulationController());

		gploop.setProgramValidator(new ProcessValidator());

		// Suspicious
		List<SuspiciousCode> candidates = projectFacade.getSuspicious(ConfigurationProperties.getProperty("packageToInstrument"),
				ProgramVariant.DEFAULT_ORIGINAL_VARIANT);
		List<SuspiciousCode> filtercandidates = new ArrayList<SuspiciousCode>();

		for (SuspiciousCode suspiciousCode : candidates) {
			if (!suspiciousCode.getClassName().endsWith("Exception")) {
				filtercandidates.add(suspiciousCode);
			}
		}

		if(candidates == null || candidates.isEmpty())
			 throw new IllegalArgumentException("No suspicious gen for analyze");
		
		gploop.initPopulation(filtercandidates);

		return gploop;
	}

	@Override
	public void run(String location, String projectName, String dependencies, String packageToInstrument, double thfl,
			String failing) throws Exception {
				
		long startT = System.currentTimeMillis();
		initProject(location, projectName, dependencies, packageToInstrument, thfl, failing);
		JGenProg gploop = null;
		String mode = ConfigurationProperties.getProperty("mode");
		if("statement".equals(mode))
			gploop = statementMode();

		else if("statement-remove".equals(mode))
			gploop = statementMode(true);
		else{
			System.out.println("Unknown mode");
			return;
		}

		ConfigurationProperties.print();
		try {
			gploop.startEvolution();
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		long endT = System.currentTimeMillis();
		log.info("Time Total(ms): " + (endT - startT));
	}

	/**
	 * @param args
	 * @throws Exception
	 * @throws ParseException
	 */
	public static void main(String[] args) throws Exception {
		System.out.println("Arguments: "+ Arrays.toString(args).replace(",", " "));
		MainjGenProg m = new MainjGenProg();
		boolean correct = m.processArguments(args);
		if(!correct){
			System.err.println("Problems with commands arguments");
			return;
		}
        if(m.isExample(args)) {
            m.executeExample(args);
            return;
        }
		

		String dependencies = ConfigurationProperties.getProperty("dependenciespath");
		String failing = ConfigurationProperties.getProperty("failing");
		String location = ConfigurationProperties.getProperty("location");
		String packageToInstrument = ConfigurationProperties.getProperty("packageToInstrument");
		double thfl = ConfigurationProperties.getPropertyDouble("flthreshold");
		String projectName = ConfigurationProperties.getProperty("projectIdentifier");

		m.run(location, projectName, dependencies, packageToInstrument, thfl, failing);
		
		
	}
	@Override
	public void run(String location, String projectName, String dependencies, String packageToInstrument)
			throws Exception {
		// TODO Auto-generated method stub
		
	}

}
