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

        stage('Install Anchore CLI') {
            steps {
                sh '''
                    if ! command -v anchore-cli > /dev/null; then
                        pip install --user anchorecli
                    fi
                '''
            }
        }

        stage('Analyze Image with Anchore') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'anchor_id', usernameVariable: 'ANCHORE_USER', passwordVariable: 'ANCHORE_PASS')]) {
                    sh '''
                        export PATH=$PATH:/home/ubuntu/.local/bin
                        anchore-cli --url ${ANCHORE_URL} --u ${ANCHORE_USER} --p ${ANCHORE_PASS} image add ${params.DOCKERHUB_USERNAME}/${params.JAVA_IMAGE_NAME}:${currentBuild.number}
                        anchore-cli --url ${ANCHORE_URL} --u ${ANCHORE_USER} --p ${ANCHORE_PASS} image wait ${params.DOCKERHUB_USERNAME}/${params.JAVA_IMAGE_NAME}:${currentBuild.number}
                        anchore-cli --url ${ANCHORE_URL} --u ${ANCHORE_USER} --p ${ANCHORE_PASS} image vuln ${params.DOCKERHUB_USERNAME}/${params.JAVA_IMAGE_NAME}:${currentBuild.number} all
                    '''
                }
            }
        }

        // Remaining stages as defined in the pipeline
        stage('Generate BOM') {
            steps {
                dir('testhello') {
                    sh 'mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom'
                }
            }
        }

        // Further stages...
    }

    post {
        always {
            echo 'Pipeline completed.'
        }

        failure {
            echo 'Build failed.'
        }

        success {
            echo 'Build succeeded.'
        }
    }
}

}
