def call() {
pipeline {
    agent any

    environment {
        DOCKERHUB_CREDENTIALS = credentials('dockerhubpwd')
        SLACK_CREDENTIALS = credentials('b3ee302b-e782-4d8e-ba83-7fa591d43205')
        DEP_TRACK_API_KEY = credentials('d_track_id')
        BOM_FILE_PATH = 'Desktop/testhello/target'
        DEP_TRACK_SERVER_URL = 'http://localhost:8025'
        DEP_TRACK_PROJECT_ID = '33d794ea-e8d3-49fd-b8f8-0c6cbce382cf'
        MAVEN_HOME = 'Maven 3.8.4'
        SONARQUBE_CREDENTIALS = credentials('sonar_d_token')
        SONARQUBE_SERVER = 'http://localhost:9000'
        ANCHORE_URL = 'http://localhost:8228'
        ANCHORE_CREDENTIALS = credentials('anchor_id')
        ANCHORE_CREDENTIALS_USR = 'admin'
        ANCHORE_CREDENTIALS_PSW = 'foobar'
    }

    parameters {
        string(name: 'JAVA_REPO', defaultValue: 'https://github.com/pramilasawant/helloword1.git', description: 'Java Application Repository')
        string(name: 'DOCKERHUB_USERNAME', defaultValue: 'pramila188', description: 'DockerHub Username')
        string(name: 'JAVA_IMAGE_NAME', defaultValue: 'testhello', description: 'Java Docker Image Name')
        string(name: 'JAVA_NAMESPACE', defaultValue: 'test', description: 'Kubernetes Namespace for Java Application')
    }

    stages {
        stage('Clone Repository') {
            steps {
                git url: params.JAVA_REPO, branch: 'main'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                dir('testhello') {
                    withSonarQubeEnv('SonarQube') {
                        sh '''
                            mvn sonar:sonar \
                                -Dsonar.projectKey=testhello \
                                -Dsonar.host.url=${SONARQUBE_SERVER} \
                                -Dsonar.login=${SONARQUBE_CREDENTIALS}
                        '''
                    }
                }
            }
        }

        stage('Build and Push Docker Image') {
            steps {
                dir('testhello') {
                    sh 'mvn clean install'
                    script {
                        def image = docker.build("${params.DOCKERHUB_USERNAME}/${params.JAVA_IMAGE_NAME}:${currentBuild.number}")
                        docker.withRegistry('', 'dockerhubpwd') {
                            image.push()
                        }
                    }
                }
            }
        }

        stage('Generate BOM') {
            steps {
                dir('testhello') {
                    sh 'mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom'
                }
            }
        }

        stage('Upload BOM to Dependency-Track') {
            steps {
                sh '''
                    curl -X POST "${DEP_TRACK_SERVER_URL}/api/v1/bom" \
                        -H "X-Api-Key: ${DEP_TRACK_API_KEY}" \
                        -H "Content-Type: multipart/form-data" \
                        -F "project=${DEP_TRACK_PROJECT_ID}" \
                        -F "bom=@${BOM_FILE_PATH}/bom.xml"
                '''
            }
        }

        stage('Install Anchore CLI') {
            steps {
                sh '''
                    if ! command -v anchore-cli > /dev/null; then
                        pip install --user anchorecli
                    fi
                '''
            }
        }

        stage('Set Anchore CLI Path') {
            steps {
                sh '''
                    export PATH=$PATH:/home/ubuntu/.local/bin
                    echo "Anchore CLI Path set to: $PATH"
                '''
            }
        }

        stage('Analyze Image with Anchore') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'anchor_id', usernameVariable: 'ANCHORE_USER', passwordVariable: 'ANCHORE_PASS')]) {
                    sh """
                        export PATH=$PATH:/home/ubuntu/.local/bin
                        anchore-cli --url ${ANCHORE_URL} --u ${ANCHORE_USER} --p ${ANCHORE_PASS} image add ${params.DOCKERHUB_USERNAME}/${params.JAVA_IMAGE_NAME}:${currentBuild.number}
                        anchore-cli --url ${ANCHORE_URL} --u ${ANCHORE_USER} --p ${ANCHORE_PASS} image wait ${params.DOCKERHUB_USERNAME}/${params.JAVA_IMAGE_NAME}:${currentBuild.number}
                        anchore-cli --url ${ANCHORE_URL} --u ${ANCHORE_USER} --p ${ANCHORE_PASS} image vuln ${params.DOCKERHUB_USERNAME}/${params.JAVA_IMAGE_NAME}:${currentBuild.number} all
                    """
                }
            }
        }

        stage('Get Approval') {
            steps {
                script {
                    input message: 'Do you approve this deployment?', ok: 'Yes, deploy'
                }
            }
        }

        stage('Install yq') {
            steps {
                sh """
                    wget https://github.com/mikefarah/yq/releases/download/v4.6.1/yq_linux_amd64 -O "${WORKSPACE}/yq"
                    chmod +x "${WORKSPACE}/yq"
                    export PATH="${WORKSPACE}:$PATH"
                """
            }
        }

        stage('Build and Package Java Helm Chart') {
            steps {
                dir('testhello') {
                    sh """
                        "${WORKSPACE}/yq" e -i '.image.tag = "latest"' ./myspringbootchart/values.yaml
                        helm template ./myspringbootchart
                        helm lint ./myspringbootchart
                        helm package ./myspringbootchart --version "1.0.0"
                    """
                }
            }
        }

        stage('Deploy Java Application to Kubernetes') {
            steps {
                script {
                    kubernetesDeploy(
                        configs: 'Build and Deploy Java and Python Applications',
                        kubeconfigId: 'kubeconfig1pwd'
                    )
                }
            }
        }
    }

    post {
        
        always {
              
                script {
                    def slackBaseUrl = 'https://slack.com/api/'
                    def slackChannel = '#builds'
                    def slackColor = currentBuild.currentResult == 'SUCCESS' ? 'good' : 'danger'
                    def slackMessage = "Build ${currentBuild.fullDisplayName} finished with status: ${currentBuild.currentResult}"

                    echo "Sending Slack notification to ${slackChannel} with message: ${slackMessage}"

                    slackSend(
                        baseUrl: 'https://yourteam.slack.com/api/',
                        teamDomain: 'StarAppleInfotech',
                        channel: '#builds',
                        color: slackColor,
                        botUser: true,
                        tokenCredentialId: SLACK_CREDENTIALS,
                        notifyCommitters: false,
                        message: "Build Java Application #${env.BUILD_NUMBER} finished with status: ${currentBuild.currentResult}"
                    )
                }
            echo 'Pipeline completed.'
            emailext(
                to: 'pramila.narawadesv@gmail.com',
                subject: "Jenkins Build ${env.JOB_NAME} #${env.BUILD_NUMBER} ${currentBuild.currentResult}",
                body: """<p>Build ${env.JOB_NAME} #${env.BUILD_NUMBER} finished with status: ${currentBuild.currentResult}</p>
                         <p>Check console output at ${env.BUILD_URL}</p>""",
                mimeType: 'text/html'
            )
        }

        failure {
            emailext(
                to: 'pramila.narawadesv@gmail.com',
                subject: "Jenkins Build ${env.JOB_NAME} #${env.BUILD_NUMBER} Failed",
                body: """<p>Build ${env.JOB_NAME} #${env.BUILD_NUMBER} failed.</p>
                         <p>Check console output at ${env.BUILD_URL}</p>""",
                mimeType: 'text/html'
            )
        }

        success {
            emailext(
                to: 'pramila.narawadesv@gmail.com',
                subject: "Jenkins Build ${env.JOB_NAME} #${env.BUILD_NUMBER} Succeeded",
                body: """<p>Build ${env.JOB_NAME} #${env.BUILD_NUMBER} succeeded.</p>
                         <p>Check console output at ${env.BUILD_URL}</p>""",
                mimeType: 'text/html'
            )
        }
    }
}


}
