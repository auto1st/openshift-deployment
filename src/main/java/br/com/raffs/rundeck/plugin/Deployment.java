/**
 * Copyleft 2017 - RafaOS (rafaeloliveira.cs@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package br.com.raffs.rundeck.plugin;

import br.com.raffs.rundeck.plugin.br.com.raffs.rundeck.plugin.core.OpenshiftClient;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope;
import com.dtolabs.rundeck.core.utils.FileUtils;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.step.StepPlugin;
import com.esotericsoftware.yamlbeans.YamlReader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.json.JSONObject;
import org.jtwig.JtwigModel;
import org.jtwig.JtwigTemplate;

import java.io.File;
import java.io.FileReader;
import java.util.*;

@Plugin(name = "openshift-deploy", service = ServiceNameConstants.WorkflowStep)
@PluginDescription(
        title = "Openshift Deployment Orchestration",
        description = "Responsible to control the Openshift Deployment Configuration and Deployment Actions"
)
public class Deployment implements StepPlugin {

    // Class provider name
    public final static String SERVICE_PROVIDER = "openshift-deployment";

    // define a list of file supported format, that will be used
    // to locate the yaml-formated file inside the source repository.
    private static final List<String> EXTENSION_SUPORTED = Collections.unmodifiableList(
            new ArrayList<String>() {{
                add("yaml");
                add("yml");
            }});

    // define the gitlab variable for the Plugin configuration.
    @PluginProperty(
            name = "git-repository",
            description = "GIT Repository URL where the configuration will be stored",
            required = true,
            scope = PropertyScope.Framework
    )
    private String gitlab_repo;             // GLOBAL

    // define the gitlab branch for the plugin configuration.
    @PluginProperty(
            name = "git-branch",
            description = "Which branch will use when cloning the repository",
            required = true,
            defaultValue = "master",
            scope = PropertyScope.Framework
    )
    private String gitlab_branch;           // GLOBAL

    // define the gitlab username for authentication
    @PluginProperty(
            name = "git-username",
            description = "Username to authenticate when cloning the GIT Repository",
            required = true,
            scope = PropertyScope.Framework
    )
    private String gitlab_username;         // GLOBAL

    // define the gitlab password for authentication
    @PluginProperty(
            name = "git-password",
            description = "Password to authenticate when cloning the GIT Repository",
            required = true,
            scope = PropertyScope.Framework
    )
    private String gitlab_password;         // GLOBAL

    // define the Gitlab Deployment file path
    @PluginProperty(
            name = "git-varpath",
            description = "The variables directory path inside the project directories",
            required = true,
            defaultValue = "vars",
            scope = PropertyScope.Framework
    )
    private String gitlab_variables_dir;    // GLOBAL

    // define the deployment configuration file
    @PluginProperty(
            name = "git-deployfile",
            description = "Deployment filename inside each project directory \n" +
                            "(Attention, Define the filename without the file extension)",
            required = true,
            defaultValue = "Deployment",
            scope = PropertyScope.Framework
    )
    private String gitlab_deployment_file;  // GLOBAL

    // define the network timeout paramters
    @PluginProperty(
            name = "network-timeout",
            description = "Network Timeout (in seconds) on try to connect with the components.",
            required = true,
            defaultValue = "30",
            scope = PropertyScope.Framework
    )
    private int network_timeout;             // GLOBAL

    // define the network max count attempts on watching the deployment
    @PluginProperty(
            name = "network-max-count-attempts",
            description = "Max attempts (in seconds) on validating the Openshift Deployment " +
                            "This values combining with network_attempts_time_interval will define " +
                            "the time that Rundeck will wait to finished the Deployment on Openshift",
            required = true,
            defaultValue = "120",
            scope = PropertyScope.Framework
    )
    private int network_max_count_attemps;   // GLOBAL

    // define the netwowrk attempts time inverval.
    @PluginProperty(
            name = "network-attempts-interval",
            description = "Define the time interval between the status attempts (in seconds)",
            required = true,
            defaultValue = "5",
            scope = PropertyScope.Framework
    )
    private int network_attempts_time_interval;  // GLOBAL

    // define the openshift server url
    @PluginProperty(
            name = "openshift-server-url",
            description = "Openshift API Manager URL (ex: https://my.openshift.com:8443",
            required = true,
            scope = PropertyScope.Framework
    )
    private String openshift_server;        // GLOBAL

    // define the openshfit api version
    @PluginProperty(
            name = "openshift-api-version",
            description = "Openshift API Version (ex: v1, v2) \n " +
                            "this will be translate to <openshift-server-url>/oapi/<version> when calling \n" +
                            "the API resources from the Openshift Server.",
            required = true,
            defaultValue = "v1",
            scope = PropertyScope.Framework
    )
    private String openshift_apiversion;    // GLOBAL

    // define the Openshfit Username
    @PluginProperty(
            name = "openshift-username",
            description = "Username to authenticate to Openshift API Manager.",
            scope = PropertyScope.Framework
    )
    private String openshift_username;      // GLOBAL

    // define the Openshift User password
    @PluginProperty(
            name = "openshift-password",
            description = "User password to authenticate to Openshift API Manager",
            scope = PropertyScope.Framework
    )
    private String openshift_password;      // GLOBAL

    // define the Openshift token access
    @PluginProperty(
            name = "openshift-access-token",
            description = "API Token authentication access \n" +
                          "When token is defined, then will ignore the username/password variables",
            scope = PropertyScope.Framework
    )
    private String openshift_token;         // GLOBAL

    // define the Openshift project name.
    @PluginProperty(
            name = "Openshift Project Name",
            description = "project name, where the services will be deployed",
            required = true
    )
    private String openshift_project;       // LOCAL

    // define the Openshift service name
    @PluginProperty(
            name = "Openshift Service Name",
            description = "service name, define the services that will be deploed",
            required = true
    )
    private String openshift_service;       // LOCAL

    // define the gitlab password for authentication
    @PluginProperty(
            name = "Environment",
            description = "Define the deployment environment, responsible to locate the " +
                        "environment file inside the variable directory",
            required = true,
            defaultValue = "development"
    )
    private String gitlab_deployment_environment;

    // define the gitlab password for authentication
    @PluginProperty(
            name = "Directory",
            description = "Define the Directory where the deployment and variables files are located",
            required = true
    )
    private String gitlab_deployment_directory;

    /**
     * Execute a step on Node
     */
    public void executeStep(final PluginStepContext context, final Map<String, Object> configuration)
            throws StepException {

        // gitlab_repo is a required parameters.
        if (gitlab_repo == null)
            throw new StepException(
                    "Configuration failed, missing the gitlab_repo variables on Rundeck Framework properties",
                    StepFailureReason.ConfigurationFailure
            );

        // gitlab_repo is a required parameters.
        if (gitlab_branch == null)
            throw new StepException(
                    "Configuration failed, missing the gitlab_branch variables on Rundeck Framework properties",
                    StepFailureReason.ConfigurationFailure
            );

        // gitlab_repo is a required parameters.
        if (gitlab_username == null)
            throw new StepException(
                    "Configuration failed, missing the gitlab_username variables on Rundeck Framework properties",
                    StepFailureReason.ConfigurationFailure
            );

        // gitlab_repo is a required parameters.
        if (gitlab_password == null)
            throw new StepException(
                    "Configuration failed, missing the gitlab_password variables on Rundeck Framework properties",
                    StepFailureReason.ConfigurationFailure
            );

        // openshift_Server is a required parameters.
        if (openshift_server == null)
            throw new StepException(
                    "Configuration failed, missing the openshift_server variables on Rundeck Framework properties",
                    StepFailureReason.ConfigurationFailure
            );

        // openshift_apiversion is a required parameters.
        if (openshift_apiversion == null)
            throw new StepException(
                    "Configuration failed, missing the openshift_apiversion variables on Rundeck Framework properties",
                    StepFailureReason.ConfigurationFailure
            );

        // One of the two authentication is need
        if (openshift_username == null && openshift_password == null && openshift_token == null)
            throw new StepException(
                    "Configuration failed, missing username/password and token variable which drive the "+
                            "Openshift Authentication on Rundeck framework properties configuration file. ",
                    StepFailureReason.ConfigurationFailure
            );

        // Define the temporary directory.
        String temp_dir = String.format("/tmp/ocdepl-%s", Long.toString(System.nanoTime()));
        String repo_dir = String.format("%s/%s", temp_dir, gitlab_deployment_directory);

        // Trying to Download the Deployment definition
        try {

            // Download the files from the Gitlab, when it's the Service Definition.
            System.out.println("Downloading repository: " + gitlab_repo + " ...");
            Git.cloneRepository()
                    .setURI(gitlab_repo)
                    .setDirectory(new File(temp_dir))
                    .setBranch(gitlab_branch)
                    .setCredentialsProvider(
                         new UsernamePasswordCredentialsProvider(gitlab_username, gitlab_password)
                    ).call();

            // Looking for the file on the plugins.
            String varsPath = null;
            for (String format : EXTENSION_SUPORTED) {
                String path = String.format(
                     "%s/%s/%s.%s",
                     repo_dir, gitlab_variables_dir, gitlab_deployment_environment, format
                );

                if (new File(path).exists()) {
                    varsPath = path;
                    break;
                }
            }

            // Looking for the deploymet variable
            String deploymentPath = null;
            for (String format : EXTENSION_SUPORTED) {
                String path = String.format("%s/%s.%s", repo_dir, gitlab_deployment_file, format);

                if (new File(path).exists()) {
                    deploymentPath = path;
                    break;
                }
            }

            // Proper way to handle errors
            if (deploymentPath == null) {
                String files = "";
                for (String format : EXTENSION_SUPORTED) {
                    files = files +
                            String.format("%s/%s.%s,", repo_dir, gitlab_deployment_file   , format);
                }

                throw new StepException(
                        String.format(
                                "Not able to found any of the file list: %s on %s",
                                files, gitlab_repo
                        ),
                        StepFailureReason.ConfigurationFailure
                );
            }

            // Instance the rundeck vars object mapping
            // allocating the plugin parameters to usage.
            JSONObject rundeckVars = new JSONObject() {{
                put("plugin", new JSONObject() {{
                    put("gitlab_repo", gitlab_repo);
                    put("gitlab_branch", gitlab_branch);
                    put("gitlab_username", gitlab_username);
                    put("gitlab_deployment_file", gitlab_deployment_file);
                    put("gitlab_variable_file", gitlab_deployment_environment);

                    put("openshift_server", openshift_server);
                    put("openshift_apiversion", openshift_apiversion);
                    put("openshift_project", openshift_project);
                    put("openshift_service", openshift_service);
                    put("openshift_username", openshift_username);
                }});
            }};

            // Read the rundeck job parameters from the JOB Context
            HashMap<String, Map<String, String>> jobContext =
                    (HashMap<String, Map<String, String>>) context.getDataContext();

            if (jobContext.get("option") != null) {
                rundeckVars.put("option", jobContext.get("option"));
            }

            // Read the environment variables into map of variables.
            Object vars = null;
            if (varsPath != null) {
                YamlReader varsReader = new YamlReader(new FileReader(varsPath));
                vars = varsReader.read();
            }

            //  Read the Deployment file using template-engine to validate the blocks and dynamic content
            JtwigTemplate template = JtwigTemplate.fileTemplate(new File(deploymentPath));
            JtwigModel model = JtwigModel.newModel()
                    .with("vars", vars)
                    .with("rundeck", rundeckVars);
            String deploymentFile = template.render(model);

            // Return the Deployment Configuration from the YAML file.
            Map deployment = (Map) new YamlReader(deploymentFile).read();
            JSONObject deployConfig = new JSONObject(deployment);

            // connect to the Openshift client
            System.out.print(String.format("Connecting to Openshift server: %s ...", openshift_server));

            // Define the Openshift
            OpenshiftClient oc = new OpenshiftClient()
                    .withProject(openshift_project)
                    .withService(openshift_service)
                    .withServerUrl(openshift_server)
                    .withApiVersion(openshift_apiversion)
                    .withTimeout(network_timeout)
                    .withToken(openshift_token)
                    .withUsername(openshift_username)
                    .withPassword(openshift_password)

                    .build();

            // Validate the service status
            int serverStatus;
            if ((serverStatus = oc.getServerStatus()) != 200) {
                throw new Exception(
                     String.format(
                             "[%d] Receive when trying to return server status", serverStatus
                     )
                );
            }
            else System.out.println("[OK]");

            // Validate whether the project exists.
            if (! oc.checkProject(openshift_project)) {
                throw new Exception(
                    String.format(
                       "Appears that project does not exists, or access ir forbidden, %s",
                       openshift_project
                    )
                );
            }

            // Create the project whether exists
            JSONObject newReleaseResponse;
            if (! oc.checkService(openshift_service)) {
                throw new StepException(
                    String.format(
                         "Unable to found the project: %s/%s !",
                            openshift_project, openshift_service
                    ), StepFailureReason.ConfigurationFailure
                );

            } else {

                // Update an already existed Deployment Configuration.
                JSONObject  currentDeploy = oc.getDeploymentConfig();
                if (currentDeploy != null) {
                    deployConfig.put("metadata", currentDeploy.getJSONObject("metadata"));

                    int latestVersion = currentDeploy.getJSONObject("status").getInt("latestVersion");
                    deployConfig.getJSONObject("status").put("latestVersion", ++latestVersion);

                    if (deployConfig.has("statusCode")) deployConfig.remove("statusCode");
                    if (deployConfig.getJSONObject("spec")
                                    .getJSONObject("template")
                                    .getJSONObject("metadata")
                                    .has("creationTimestamp")) {

                            deployConfig.getJSONObject("spec")
                                    .getJSONObject("template")
                                    .getJSONObject("metadata")
                                    .remove("creationTimestamp");
                    }

                    newReleaseResponse = oc.setDeploymentConfig(deployConfig);
                }
                else {
                    throw new Exception(
                        "Error on try to get the Deployment Configuration"
                    );
                }

            }

            // Watch the deployment
            if (newReleaseResponse.has("status")) {
                if (newReleaseResponse.getJSONObject("status").has("latestVersion")) {
                    System.out.println(String.format("Deployment #%d running, this could take some while...",
                            newReleaseResponse.getJSONObject("status").getInt("latestVersion")));
                }
            }

            // Watching the Deployment of the Services.
            int attempts = 0;
            while (oc.notReady()) {
                if (attempts >= network_max_count_attemps) {
                    throw new StepException(
                            "Openshift deployment took to long to finished. ",
                            StepFailureReason.PluginFailed
                    );
                }
                else Thread.sleep(network_attempts_time_interval * 1000);

                attempts += 1;
            }

            dumpStatus(oc.getDeploymentConfig());
        }
        catch (Exception ex) {
            throw new StepException(
                    "Error on trying automate Openshift Deployment & Provision\nError msg: " + ex.getMessage(),
                    StepFailureReason.PluginFailed
            );
        }
        finally {

            // House cleaning when need.
            try {
                FileUtils.deleteDir(new File(repo_dir));
            }
            catch (Exception ex) { /* Do NOTHING */ }
        }
    }

    /**
     * Dump information about the deployment
     *
     * @param deploymentConfig
     */
    private void dumpStatus(JSONObject deploymentConfig) {
        System.out.println("\n+========== Deployment Successfully Finished ==========+");

        try {

            System.out.println("Name: " + deploymentConfig
                    .getJSONObject("metadata").getString("name"));

            System.out.println("Namespace: " + deploymentConfig
                    .getJSONObject("metadata").getString("namespace"));

            System.out.println("Latest Version: " + deploymentConfig
                    .getJSONObject("status").getInt("latestVersion"));

            System.out.println("Deployed Image: " + deploymentConfig
                    .getJSONObject("spec").getJSONObject("template")
                    .getJSONObject("spec").getJSONArray("containers")
                    .getJSONObject(0).getString("image"));

            System.out.println("Updated Replicas: " + deploymentConfig
                    .getJSONObject("status").get("updatedReplicas"));

            System.out.println("Available Replicas: " + deploymentConfig
                    .getJSONObject("status").get("availableReplicas"));
        }
        catch (Exception ex) {/* do nothing */}

        // That it.
        System.out.println(
                String.format(
                        "========== + Updated the resource: %s/%s successfully ==========+",
                        openshift_project, openshift_service
                )
        );
    }
}
